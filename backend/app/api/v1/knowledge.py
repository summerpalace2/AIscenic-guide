from fastapi import APIRouter, Depends, HTTPException, Query
from typing import Optional
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.schemas.knowledge import KnowledgeCreate, KnowledgeUpdate
from app.services.knowledge_service import knowledge_service
from app.api.deps import get_admin_user
from app.models.user import User

router = APIRouter(prefix='/knowledge', tags=['Knowledge'])

@router.get('')
async def list_knowledge(category: Optional[str] = None, keyword: Optional[str] = None, page: int = Query(1), size: int = Query(20), admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': knowledge_service.get_list(db, category, keyword, page, size)}

@router.get('/categories')
async def get_categories(admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': knowledge_service.get_categories(db)}

@router.get('/{doc_id}')
async def get_document(doc_id: str, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    try:
        doc = knowledge_service.get_detail(db, doc_id)
        return {'code': 0, 'success': True, 'message': 'OK', 'data': {'id': str(doc.id), 'title': doc.title, 'category': doc.category, 'content': doc.content, 'file_url': doc.file_url, 'status': doc.status, 'vector_status': doc.vector_status, 'tags': doc.tags or [], 'created_at': doc.created_at, 'updated_at': doc.updated_at}}
    except ValueError as e:
        raise HTTPException(status_code=404, detail={'code': 40402, 'success': False, 'message': str(e)})

@router.post('', status_code=201)
async def create_document(req: KnowledgeCreate, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    doc = knowledge_service.create(db, req, str(admin.id))
    return {'code': 0, 'success': True, 'message': 'Created', 'data': {'id': str(doc.id)}}

@router.put('/{doc_id}')
async def update_document(doc_id: str, req: KnowledgeUpdate, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    try:
        knowledge_service.update(db, doc_id, req)
        return {'code': 0, 'success': True, 'message': 'Updated'}
    except ValueError as e:
        raise HTTPException(status_code=404, detail={'code': 40402, 'success': False, 'message': str(e)})

@router.delete('/{doc_id}')
async def delete_document(doc_id: str, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    try:
        knowledge_service.delete(db, doc_id)
        return {'code': 0, 'success': True, 'message': 'Deleted', 'data': None}
    except ValueError as e:
        raise HTTPException(status_code=404, detail={'code': 40402, 'success': False, 'message': str(e)})

@router.post('/{doc_id}/sync')
async def trigger_sync(doc_id: str, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    try:
        data = knowledge_service.trigger_sync(db, doc_id)
        return {'code': 0, 'success': True, 'message': 'Sync triggered', 'data': data}
    except ValueError as e:
        raise HTTPException(status_code=404, detail={'code': 40402, 'success': False, 'message': str(e)})
