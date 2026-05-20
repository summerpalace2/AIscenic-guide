import uuid
from typing import Optional
from sqlalchemy.orm import Session
from sqlalchemy import or_ as sql_or
from app.models.knowledge import KnowledgeDocument
from app.schemas.knowledge import KnowledgeCreate, KnowledgeUpdate

class KnowledgeService:
    def get_list(self, db: Session, category=None, keyword=None, page=1, size=20) -> dict:
        q = db.query(KnowledgeDocument).filter(KnowledgeDocument.status != chr(34)+'archived'+chr(34))
        if category: q = q.filter(KnowledgeDocument.category == category)
        if keyword: q = q.filter(sql_or(KnowledgeDocument.title.ilike(f'%{keyword}%'), KnowledgeDocument.content.ilike(f'%{keyword}%')))
        total = q.count()
        items = q.order_by(KnowledgeDocument.updated_at.desc()).offset((page-1)*size).limit(size).all()
        return {'total': total, 'page': page, 'size': size, 'items': [{'id': str(d.id), 'title': d.title, 'category': d.category, 'content_snippet': (d.content or '')[:100], 'status': d.status, 'created_at': d.created_at, 'updated_at': d.updated_at} for d in items]}

    def get_detail(self, db: Session, doc_id: str) -> KnowledgeDocument:
        doc = db.query(KnowledgeDocument).filter(KnowledgeDocument.id == doc_id).first()
        if not doc: raise ValueError('Document not found')
        return doc

    def create(self, db: Session, req: KnowledgeCreate, user_id: str) -> KnowledgeDocument:
        doc = KnowledgeDocument(id=uuid.uuid4(), title=req.title, category=req.category, content=req.content or '', tags=req.tags or [], created_by=uuid.UUID(user_id))
        db.add(doc); db.commit(); db.refresh(doc)
        return doc

    def update(self, db: Session, doc_id: str, req: KnowledgeUpdate) -> KnowledgeDocument:
        doc = self.get_detail(db, doc_id)
        for f, v in req.model_dump(exclude_unset=True).items(): setattr(doc, f, v)
        doc.vector_status = 'pending'; db.commit(); db.refresh(doc)
        return doc

    def delete(self, db: Session, doc_id: str):
        doc = self.get_detail(db, doc_id); doc.status = 'archived'; db.commit()

    def get_categories(self, db: Session) -> list:
        from sqlalchemy import func
        results = db.query(KnowledgeDocument.category, func.count(KnowledgeDocument.id)).filter(KnowledgeDocument.status != 'archived').group_by(KnowledgeDocument.category).all()
        label_map = {'history': 'History', 'culture': 'Culture', 'faq': 'FAQ', 'notice': 'Notice'}
        return [{'key': r[0], 'label': label_map.get(r[0], r[0]), 'count': r[1]} for r in results]

    def trigger_sync(self, db: Session, doc_id: str) -> dict:
        doc = self.get_detail(db, doc_id); doc.vector_status = 'syncing'; db.commit()
        return {'doc_id': str(doc.id), 'vector_status': 'syncing', 'chunk_count': 0}

knowledge_service = KnowledgeService()
