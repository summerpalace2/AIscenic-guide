import hashlib
import os
from fastapi import APIRouter, Depends, HTTPException, Query, UploadFile, File, Form
from typing import Optional
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.schemas.knowledge import KnowledgeCreate, KnowledgeUpdate
from app.services.knowledge_service import knowledge_service
from app.services.file_storage import file_storage
from app.services.ai_client import ai_client, DOC_EXTENSIONS
from app.api.deps import get_admin_user
from app.models.user import User
from app.models.knowledge import KnowledgeDocument

router = APIRouter(prefix='/knowledge', tags=['Knowledge'])

async def _auto_sync(file_url: str, doc_id: str, db: Session):
    """上传/更新后自动触发向量化，失败不阻塞主流程"""
    file_path = file_storage.get_path(file_url) if file_url else ''
    if not file_path:
        return
    # 非文档格式（图片/音频等）跳过向量化
    ext = os.path.splitext(file_path)[1].lower()
    if ext not in DOC_EXTENSIONS:
        return
    try:
        result = await ai_client.import_document(file_path)
        if result.get('success'):
            doc = db.query(KnowledgeDocument).filter(KnowledgeDocument.id == doc_id).first()
            if doc:
                doc.vector_status = 'synced'
                db.commit()
    except Exception as e:
        print(f'[AutoSync] 向量化失败（非致命）: {e}')

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
        return {'code': 0, 'success': True, 'message': 'OK', 'data': {'id': str(doc.id), 'title': doc.title, 'category': doc.category, 'content': doc.content, 'file_url': doc.file_url, 'status': doc.status, 'vector_status': doc.vector_status, 'tags': doc.tags or [], 'chunk_count': doc.chunk_count or 0, 'created_at': doc.created_at, 'updated_at': doc.updated_at}}
    except ValueError as e:
        raise HTTPException(status_code=404, detail={'code': 40402, 'success': False, 'message': str(e)})

@router.post('', status_code=201)
async def create_document(
    title: str = Form(...),
    category: str = Form(...),
    content: Optional[str] = Form(None),
    tags: Optional[str] = Form(None),
    file: Optional[UploadFile] = File(None),
    admin: User = Depends(get_admin_user),
    db: Session = Depends(get_db),
):
    """创建/上传知识文档，支持直接输入文本或上传文件（multipart/form-data）"""
    try:
        file_url = ''
        file_md5 = ''
        if file:
            raw_bytes = await file.read()
            file_md5 = hashlib.md5(raw_bytes).hexdigest()
            existing = db.query(KnowledgeDocument).filter(
                KnowledgeDocument.file_md5 == file_md5,
                KnowledgeDocument.status != 'archived'
            ).first()
            if existing:
                raise HTTPException(status_code=400, detail={
                    'code': 40001, 'success': False,
                    'message': f'文件已存在（文档: {existing.title}）', 'data': {'id': str(existing.id)}
                })
            file.file.seek(0)
            file_url = await file_storage.save(file, sub_dir='knowledge')

        tag_list = [t.strip() for t in tags.split(',') if t.strip()] if tags else []
        req = KnowledgeCreate(title=title, category=category, content=content or '', tags=tag_list, file_url=file_url, file_md5=file_md5)
        doc = knowledge_service.create(db, req, str(admin.id))
        if file_url:
            await _auto_sync(file_url, doc.id, db)
        return {
            'code': 0, 'success': True, 'message': 'Created',
            'data': {
                'id': str(doc.id), 'title': doc.title, 'category': doc.category,
                'content': doc.content, 'file_url': doc.file_url,
                'status': doc.status, 'vector_status': doc.vector_status,
                'tags': doc.tags or [], 'chunk_count': doc.chunk_count or 0,
                'created_at': doc.created_at, 'updated_at': doc.updated_at
            }
        }
    except ValueError as e:
        raise HTTPException(status_code=400, detail={'code': 40001, 'success': False, 'message': str(e)})

@router.put('/{doc_id}')
async def update_document(doc_id: str, req: KnowledgeUpdate, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    try:
        updated = knowledge_service.update(db, doc_id, req)
        # 自动触发重新向量化（非阻塞）
        await _auto_sync(updated.file_url, updated.id, db)
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
        data = await knowledge_service.trigger_sync(db, doc_id)
        return {'code': 0, 'success': True, 'message': 'Sync triggered', 'data': data}
    except ValueError as e:
        raise HTTPException(status_code=404, detail={'code': 40402, 'success': False, 'message': str(e)})
