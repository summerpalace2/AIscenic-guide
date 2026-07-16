import uuid
from sqlalchemy.orm import Session
from sqlalchemy import or_ as sql_or
from app.models.knowledge import KnowledgeDocument
from app.schemas.knowledge import KnowledgeCreate, KnowledgeUpdate
from app.services.ai_client import ai_client
from app.services.file_storage import file_storage

class KnowledgeService:
    def get_list(self, db: Session, category=None, keyword=None, page=1, size=20) -> dict:
        q = db.query(KnowledgeDocument).filter(KnowledgeDocument.status != chr(34)+'archived'+chr(34))
        if category:
            q = q.filter(KnowledgeDocument.category == category)
        if keyword:
            q = q.filter(sql_or(KnowledgeDocument.title.ilike(f'%{keyword}%'), KnowledgeDocument.content.ilike(f'%{keyword}%')))
        total = q.count()
        items = q.order_by(KnowledgeDocument.updated_at.desc()).offset((page-1)*size).limit(size).all()
        return {'total': total, 'page': page, 'size': size, 'items': [{'id': str(d.id), 'title': d.title, 'category': d.category, 'content_snippet': (d.content or '')[:100], 'status': d.status, 'chunk_count': d.chunk_count or 0, 'created_at': d.created_at, 'updated_at': d.updated_at} for d in items]}

    def get_detail(self, db: Session, doc_id: str) -> KnowledgeDocument:
        doc = db.query(KnowledgeDocument).filter(KnowledgeDocument.id == uuid.UUID(doc_id)).first()
        if not doc:
            raise ValueError('Document not found')
        return doc

    def create(self, db: Session, req: KnowledgeCreate, user_id: str) -> KnowledgeDocument:
        existing = db.query(KnowledgeDocument).filter(
            KnowledgeDocument.title == req.title,
            KnowledgeDocument.status != 'archived'
        ).first()
        if existing:
            raise ValueError(f'已存在同名文档: {req.title}')
        doc = KnowledgeDocument(id=uuid.uuid4(), title=req.title, category=req.category,
                                content=req.content or '', tags=req.tags or [],
                                file_md5=req.file_md5 or '',
                                created_by=uuid.UUID(user_id))
        db.add(doc)
        db.commit()
        db.refresh(doc)
        return doc

    def update(self, db: Session, doc_id: str, req: KnowledgeUpdate) -> KnowledgeDocument:
        doc = self.get_detail(db, doc_id)
        for f, v in req.model_dump(exclude_unset=True).items():
            setattr(doc, f, v)
        doc.vector_status = 'pending'
        db.commit()
        db.refresh(doc)
        return doc

    def delete(self, db: Session, doc_id: str):
        doc = self.get_detail(db, doc_id)
        doc.status = 'archived'
        db.commit()

    def get_categories(self, db: Session) -> list:
        from sqlalchemy import func
        results = db.query(KnowledgeDocument.category, func.count(KnowledgeDocument.id)).filter(KnowledgeDocument.status != 'archived').group_by(KnowledgeDocument.category).all()
        label_map = {'history': '历史文化', 'culture': '人文艺术', 'faq': '常见问题', 'notice': '游览须知'}
        return [{'key': r[0], 'label': label_map.get(r[0], r[0]), 'count': r[1]} for r in results]

    async def trigger_sync(self, db: Session, doc_id: str) -> dict:
        doc = self.get_detail(db, doc_id)
        doc.vector_status = 'syncing'
        db.commit()

        # 将 file_url 转为绝对路径，传给 AI 端导入
        file_path = file_storage.get_path(doc.file_url) if doc.file_url else ''
        if file_path:
            try:
                result = await ai_client.import_document(file_path)
                doc.vector_status = 'synced' if result.get('success') else 'failed'
            except Exception as e:
                doc.vector_status = 'failed'
                print(f'[Sync] AI 端导入失败: {e}')
        else:
            doc.vector_status = 'failed'

        db.commit()
        return {'doc_id': str(doc.id), 'vector_status': doc.vector_status, 'chunk_count': doc.chunk_count or 0}

knowledge_service = KnowledgeService()
