"""Frozen local document retrieval experiment; never reads provisional relevance labels."""
import argparse
import gc
import hashlib
import importlib.metadata
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import time
import unicodedata


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"))


def normalized(text):
    return unicodedata.normalize("NFKC", text).lower()


def bigrams(text):
    result = []
    for segment in re.findall(r"[^\W_]+", normalized(text), re.UNICODE):
        result.extend([segment] if len(segment) == 1 else [segment[i:i + 2] for i in range(len(segment) - 1)])
    return result


def ordered(ids, scores, limit):
    if len(ids) != len(set(ids)) or len(ids) != len(scores):
        raise ValueError("Scores require unique document identities")
    if not all(math.isfinite(float(v)) for v in scores):
        raise ValueError("Non-finite ranking score")
    pairs = sorted(zip(ids, scores), key=lambda pair: (-float(pair[1]), pair[0]))[:limit]
    return [{"docId": did, "rank": rank, "score": float(score)} for rank, (did, score) in enumerate(pairs, 1)]


def rrf(lists, k, limit):
    scores = {}
    for hits in lists:
        seen = set()
        for hit in hits:
            if hit["docId"] in seen:
                raise ValueError("A retrieval branch contains duplicate documents")
            seen.add(hit["docId"])
            scores[hit["docId"]] = scores.get(hit["docId"], 0) + 1 / (k + hit["rank"])
    return ordered(list(scores), list(scores.values()), limit)


def public_queries(raw):
    # Explicit projection excludes old answerability/gradedRelevantDocuments/hard-negative labels.
    rows = [{key: q[key] for key in ["id", "query", "city", "split", "familyId"]} for q in raw["queries"]]
    if not rows or len({q["id"] for q in rows}) != len(rows):
        raise ValueError("Empty or duplicate query set")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--runtime-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    config_bytes = args.config.read_bytes()
    config_hash = hashlib.sha256(config_bytes).hexdigest()
    config = json.loads(config_bytes.decode("utf-8-sig"))
    if config["schemaVersion"] != "local-retrieval-experiment-v1":
        raise ValueError("Unsupported experiment config")
    root = Path(__file__).resolve().parents[2]
    inputs = {}
    for name, item in config["inputs"].items():
        path = root / item["path"]
        if sha(path) != item["sha256"]:
            raise ValueError(f"Frozen input changed: {name}")
        inputs[name] = path
    if sha(__file__) != config["runnerSha256"]:
        raise ValueError("Runner changed since pre-registration")
    corpus = [json.loads(line) for line in inputs["corpus"].read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    manifest, query_file = load_json(inputs["manifest"]), load_json(inputs["queries"])
    if manifest["corpusSha256"] != sha(inputs["corpus"]) or query_file["corpusSha256"] != sha(inputs["corpus"]):
        raise ValueError("Corpus identity mismatch")
    queries = public_queries(query_file)
    docs = {d["docId"]: d for d in corpus}
    if len(docs) != len(corpus) or set(docs) != {d["docId"] for d in manifest["documents"]}:
        raise ValueError("Corpus ID mismatch")
    if any(q["city"] != config["scope"]["primaryCity"] for q in queries):
        raise ValueError("This pre-registered run is single-city only; separate other-city runs")
    for d in corpus:
        if hashlib.sha256(d["content"].encode("utf-8")).hexdigest() != next(x["contentSha256"] for x in manifest["documents"] if x["docId"] == d["docId"]):
            raise ValueError("Document content identity mismatch")
    args.output_dir.mkdir(parents=True, exist_ok=False)
    (args.output_dir / "config.frozen.json").write_bytes(config_bytes)
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    os.environ["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
    import numpy as np
    import torch
    import jieba
    from rank_bm25 import BM25Okapi
    from transformers import AutoModel, AutoModelForSequenceClassification, AutoTokenizer

    for package, version in config["dependencies"].items():
        if importlib.metadata.version(package) != version:
            raise ValueError(f"Dependency drift: {package}")
    dictionary = Path(jieba.__file__).parent / "dict.txt"
    if sha(dictionary) != config["bm25"]["jiebaDictionarySha256"]:
        raise ValueError("jieba dictionary changed")
    cutter = jieba.Tokenizer(str(dictionary))
    cutter.cache_file = str(args.output_dir / "jieba.cache")
    cutter.initialize()

    def jieba_tokens(text):
        return [token for token in cutter.cut(normalized(text), HMM=False) if any(c.isalnum() for c in token)]

    helper_path = inputs["modelHelper"]
    spec_loader = importlib.util.spec_from_file_location("model_smoke_helpers", helper_path)
    helpers = importlib.util.module_from_spec(spec_loader)
    spec_loader.loader.exec_module(helpers)
    model_config = load_json(inputs["models"])
    model_hashes = {role: helpers.verify_acquisition(args.runtime_root / "models" / role, model_config[role]) for role in ["embedding", "reranker"]}
    torch.set_num_threads(config["execution"]["threads"])
    torch.manual_seed(config["execution"]["seed"])
    np.random.seed(config["execution"]["seed"])
    torch.use_deterministic_algorithms(True)
    load_start = time.perf_counter()
    embedding_path = args.runtime_root / "models" / "embedding"
    reranker_path = args.runtime_root / "models" / "reranker"
    embed_tokenizer = AutoTokenizer.from_pretrained(embedding_path, local_files_only=True, trust_remote_code=False)
    embed_model = AutoModel.from_pretrained(embedding_path, local_files_only=True, trust_remote_code=False, use_safetensors=True).eval()
    rerank_tokenizer = AutoTokenizer.from_pretrained(reranker_path, local_files_only=True, trust_remote_code=False)
    rerank_model = AutoModelForSequenceClassification.from_pretrained(reranker_path, local_files_only=True, trust_remote_code=False, use_safetensors=True).eval()
    load_ms = (time.perf_counter() - load_start) * 1000

    def encode(texts):
        blocks = []
        for offset in range(0, len(texts), config["execution"]["embeddingBatchSize"]):
            batch = embed_tokenizer(texts[offset:offset + config["execution"]["embeddingBatchSize"]], padding=True, truncation=True,
                                    max_length=config["text"]["maxTokens"], return_tensors="pt")
            with torch.inference_mode():
                vector = torch.nn.functional.normalize(embed_model(**batch).last_hidden_state[:, 0], p=2, dim=1)
            if not torch.isfinite(vector).all():
                raise ValueError("Embedding contains non-finite values")
            blocks.append(vector.numpy())
        return np.concatenate(blocks)

    ids = sorted(docs)
    texts = {did: docs[did]["title"] + "\n" + docs[did]["content"] for did in ids}
    document_lengths = embed_tokenizer([texts[did] for did in ids], truncation=False, return_length=True)["length"]
    index_start = time.perf_counter()
    vectors = encode([texts[did] for did in ids])
    np.save(args.output_dir / "document-vectors.npy", vectors)
    index = {did: i for i, did in enumerate(ids)}
    city_ids = {city: [did for did in ids if docs[did]["city"] == city] for city in {q["city"] for q in queries}}
    city_bm25 = {}
    for city, local_ids in city_ids.items():
        city_bm25[city] = {name: BM25Okapi([tokenizer(texts[did]) for did in local_ids],
                                       k1=config["bm25"]["k1"], b=config["bm25"]["b"], epsilon=config["bm25"]["epsilon"])
                           for name, tokenizer in [("bm25_jieba", jieba_tokens), ("bm25_bigram", bigrams)]}
    index_ms = (time.perf_counter() - index_start) * 1000
    encode([config["text"]["queryPrefix"] + queries[0]["query"]])
    warm = rerank_tokenizer([[queries[0]["query"], texts[city_ids[queries[0]["city"]][0]]]], padding=True, truncation=True,
                            max_length=config["text"]["maxTokens"], return_tensors="pt")
    with torch.inference_mode():
        rerank_model(**warm)
    report = {
        "schemaVersion": "retrieval-rankings-v1", "configSha256": config_hash, "corpusSha256": sha(inputs["corpus"]),
        "queriesSha256": sha(inputs["queries"]), "runnerSha256": sha(__file__),
        "scope": config["scope"], "config": config, "models": {role: {"spec": model_config[role], "filesSha256": model_hashes[role]} for role in model_hashes},
        "index": {"documents": len(ids), "sameCityCandidates": {city: len(local_ids) for city, local_ids in city_ids.items()},
                  "vectorsSha256": sha(args.output_dir / "document-vectors.npy"), "buildMs": index_ms, "modelLoadMs": load_ms},
        "visibility": {"bm25": "Full title plus content; no model token cap", "dense": "Title plus content truncated to maxTokens",
                       "reranker": "Query/document pair uses tokenizer longest_first truncation to maxTokens",
                       "maxTokens": config["text"]["maxTokens"],
                       "denseDocumentTokenLengths": dict(zip(ids, document_lengths)),
                       "denseDocumentsTruncated": sum(n > config["text"]["maxTokens"] for n in document_lengths)},
        "quality": "NOT_SCORED_PENDING_BLIND_POOLED_JUDGMENTS", "runs": [],
        "latencyScope": "CPU warm-index component composition; shared dense/BM25 stages reused within query; excludes model load/index build. Not HTTP or independent end-to-end timing.",
    }
    progress_path = args.output_dir / "progress.jsonl"
    for repeat in range(1, config["execution"]["repeats"] + 1):
        run = {"repeat": repeat, "queries": []}
        for number, q in enumerate(queries, 1):
            row = {**q, "methods": {}, "stages": {}}
            started = time.perf_counter()
            local_ids = city_ids[q["city"]]
            current_stage = "dense"
            try:
                stage = time.perf_counter()
                row["denseQueryOriginalTokens"] = embed_tokenizer(config["text"]["queryPrefix"] + q["query"], truncation=False, return_length=True)["length"][0]
                query_vector = encode([config["text"]["queryPrefix"] + q["query"]])[0]
                dense = ordered(local_ids, vectors[[index[did] for did in local_ids]] @ query_vector, config["retrieval"]["branchDepth"])
                row["stages"]["denseMs"] = (time.perf_counter() - stage) * 1000
                row["methods"]["dense"] = {"status": "OK", "ranking": dense[:config["retrieval"]["outputDepth"]], "latencyMs": row["stages"]["denseMs"]}
                lexical = {}
                for name, tokenizer in [("bm25_jieba", jieba_tokens), ("bm25_bigram", bigrams)]:
                    current_stage = name
                    stage = time.perf_counter()
                    lexical[name] = ordered(local_ids, city_bm25[q["city"]][name].get_scores(tokenizer(q["query"])), config["retrieval"]["branchDepth"])
                    row["stages"][name + "Ms"] = (time.perf_counter() - stage) * 1000
                    row["methods"][name] = {"status": "OK", "ranking": lexical[name][:config["retrieval"]["outputDepth"]], "latencyMs": row["stages"][name + "Ms"]}
                stage = time.perf_counter()
                current_stage = "hybrid_rrf"
                fused = rrf([dense, lexical["bm25_jieba"]], config["retrieval"]["rrfK"], config["retrieval"]["rerankDepth"])
                row["stages"]["fusionMs"] = (time.perf_counter() - stage) * 1000
                fusion_latency = row["stages"]["denseMs"] + row["stages"]["bm25_jiebaMs"] + row["stages"]["fusionMs"]
                row["methods"]["hybrid_rrf"] = {"status": "OK", "ranking": fused, "latencyMs": fusion_latency}
                row["branchCandidates"] = {"dense": dense, "bm25_jieba": lexical["bm25_jieba"]}
                row["fusionUnionCount"] = len({h["docId"] for h in dense + lexical["bm25_jieba"]})
                stage = time.perf_counter()
                current_stage = "hybrid_rerank"
                rerank_scores = []
                row["rerankPairOriginalTokens"] = []
                for offset in range(0, len(fused), config["execution"]["rerankBatchSize"]):
                    pairs = [[q["query"], texts[h["docId"]]] for h in fused[offset:offset + config["execution"]["rerankBatchSize"]]]
                    lengths = rerank_tokenizer(pairs, truncation=False, return_length=True)["length"]
                    row["rerankPairOriginalTokens"].extend({"docId": h["docId"], "tokens": n} for h, n in zip(fused[offset:offset + config["execution"]["rerankBatchSize"]], lengths))
                    batch = rerank_tokenizer(pairs, padding=True, truncation=True, max_length=config["text"]["maxTokens"], return_tensors="pt")
                    with torch.inference_mode():
                        rerank_scores.extend(rerank_model(**batch).logits.view(-1).float().tolist())
                row["stages"]["rerankMs"] = (time.perf_counter() - stage) * 1000
                row["methods"]["hybrid_rerank"] = {"status": "OK", "ranking": ordered([h["docId"] for h in fused], rerank_scores, config["retrieval"]["outputDepth"]),
                                                       "latencyMs": fusion_latency + row["stages"]["rerankMs"]}
            except Exception as exc:
                for name in config["methods"]:
                    if name not in row["methods"]:
                        row["methods"][name] = {"status": "ERROR", "ranking": [], "latencyMs": None,
                                                "errorKind": "STAGE_EXCEPTION" if name == current_stage else "NOT_ATTEMPTED_AFTER_FAILURE",
                                                "failedStage": current_stage, "error": type(exc).__name__ + ": " + str(exc)}
            row["wallMs"] = (time.perf_counter() - started) * 1000
            run["queries"].append(row)
            with progress_path.open("a", encoding="utf-8") as stream:
                stream.write(json.dumps({"repeat": repeat, "queryId": q["id"], "finished": number, "total": len(queries),
                                         "wallMs": row["wallMs"], "methods": {name: value["status"] for name, value in row["methods"].items()}}) + "\n")
            if number % 10 == 0:
                print(f"repeat {repeat}: {number}/{len(queries)} queries", flush=True)
        report["runs"].append(run)
        with (args.output_dir / f"repeat-{repeat}.json").open("x", encoding="utf-8") as stream:
            json.dump(run, stream, ensure_ascii=False, indent=2)
    for name, path in inputs.items():
        if sha(path) != config["inputs"][name]["sha256"]:
            raise ValueError(f"Input changed during run: {name}")
    if sha(__file__) != config["runnerSha256"]:
        raise ValueError("Runner changed during run")
    if sha(args.config) != config_hash:
        raise ValueError("Configuration changed during run")
    with (args.output_dir / "rankings.json").open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    failures = sum(r["status"] != "OK" for run in report["runs"] for q in run["queries"] for r in q["methods"].values())
    print(json.dumps({"status": "RANKINGS_RECORDED_NOT_QUALITY_ACCEPTANCE", "methodErrors": failures,
                      "queryCount": len(queries), "repeats": len(report["runs"])}, ensure_ascii=False))
    if failures:
        raise SystemExit(2)
    del embed_model, rerank_model
    gc.collect()


if __name__ == "__main__":
    main()
