"""Optional isolated real-model smoke. No knowledge DB, index, qrels or quality scoring."""
import argparse
import gc
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import re
import time


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def record_acquisition(directory, spec):
    receipt = {"schemaVersion": "retrieval-model-acquisition-v1", "repoId": spec["repoId"],
               "revision": spec["revision"], "filesSha256": {name: digest(directory / name) for name in spec["files"]}}
    temporary = directory / "acquisition.json.tmp"
    temporary.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    temporary.replace(directory / "acquisition.json")


def verify_acquisition(directory, spec):
    receipt = json.loads((directory / "acquisition.json").read_text(encoding="utf-8"))
    if (receipt.get("schemaVersion"), receipt.get("repoId"), receipt.get("revision")) != (
            "retrieval-model-acquisition-v1", spec["repoId"], spec["revision"]):
        raise ValueError("Local model acquisition revision differs from configuration")
    actual = {name: digest(directory / name) for name in spec["files"]}
    if receipt.get("filesSha256") != actual:
        raise ValueError("Local model bytes differ from acquisition receipt")
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--runtime-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Output already exists; choose a new run path")
    config = json.loads(args.config.read_text(encoding="utf-8-sig"))
    if config.get("schemaVersion") != "retrieval-local-models-v1":
        raise ValueError("Unsupported model configuration")
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    os.environ["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
    os.environ["HF_HUB_DISABLE_XET"] = "1"
    if not args.download:
        os.environ["HF_HUB_OFFLINE"] = "1"
    import torch
    from transformers import AutoModel, AutoModelForSequenceClassification, AutoTokenizer

    torch.set_num_threads(config["threads"])
    torch.manual_seed(0)
    report = {
        "schemaVersion": "retrieval-model-smoke-v1",
        "configSha256": digest(args.config), "scriptSha256": digest(Path(__file__)),
        "device": "cpu", "scope": "Real inference smoke only; no corpus retrieval or quality metric",
        "dependencies": {p: importlib.metadata.version(p) for p in ["torch", "transformers", "safetensors", "huggingface-hub"]},
        "models": {},
    }
    for role in ["embedding", "reranker"]:
        spec = config[role]
        if not re.fullmatch(r"[a-f0-9]{40}", spec["revision"]):
            raise ValueError("A full immutable model commit is required")
        directory = args.runtime_root / "models" / role
        if args.download:
            from huggingface_hub import HfApi, snapshot_download
            info = HfApi().model_info(spec["repoId"], revision=spec["revision"], token=False)
            if info.sha != spec["revision"]:
                raise ValueError("Model revision mismatch")
            card = info.card_data.to_dict() if info.card_data else {}
            if card.get("license") != spec["license"]:
                raise ValueError("Model license metadata changed")
            snapshot_download(repo_id=spec["repoId"], revision=spec["revision"], token=False,
                              local_dir=directory, allow_patterns=spec["files"], max_workers=2)
            record_acquisition(directory, spec)
        for name in spec["files"]:
            if not (directory / name).is_file():
                raise ValueError(f"Missing {role} model file: {name}")
        file_hashes = verify_acquisition(directory, spec)
        tokenizer = AutoTokenizer.from_pretrained(directory, local_files_only=True, trust_remote_code=False)
        cls = AutoModel if role == "embedding" else AutoModelForSequenceClassification
        start = time.perf_counter()
        model = cls.from_pretrained(directory, local_files_only=True, trust_remote_code=False,
                                    use_safetensors=True).to("cpu").eval()
        load_ms = (time.perf_counter() - start) * 1000
        texts = ["园区说明包含开放时间。", "馆藏主题介绍不涉及开放安排。"]
        inputs = tokenizer(texts, padding=True, truncation=True, max_length=config["maxLength"], return_tensors="pt") if role == "embedding" else tokenizer(
            [["几点可以入园？", text] for text in texts], padding=True, truncation=True,
            max_length=config["maxLength"], return_tensors="pt")
        with torch.inference_mode():
            model(**inputs)  # Warm-up is deliberately excluded from inferenceMs.
            start = time.perf_counter()
            prediction = model(**inputs)
            inference_ms = (time.perf_counter() - start) * 1000
            if role == "embedding":
                values = torch.nn.functional.normalize(prediction.last_hidden_state[:, 0], p=2, dim=1)
                shape = list(values.shape)
                norms = values.norm(dim=1).tolist()
                assert shape == [2, spec["dimension"]] and torch.isfinite(values).all()
                assert all(abs(v - 1) < 1e-5 for v in norms)
                result = {"shape": shape, "l2Norms": norms, "pooling": "CLS_then_L2"}
            else:
                values = prediction.logits.view(-1).float().tolist()
                assert len(values) == 2 and all(math.isfinite(v) for v in values)
                result = {"scores": values, "meaning": "Uncalibrated relevance logits, not probabilities"}
        report["models"][role] = {**spec, "filesSha256": file_hashes,
                                   "loadMs": load_ms, "inferenceMs": inference_ms, "batchSize": 2,
                                   "status": "REAL_INFERENCE_PASSED", **result}
        del model, tokenizer, prediction, inputs
        gc.collect()
    report["status"] = "PASS_SMOKE_ONLY_NOT_RETRIEVAL_ACCEPTANCE"
    with args.output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(json.dumps({"status": report["status"], "models": list(report["models"])}, ensure_ascii=False))


if __name__ == "__main__":
    main()
