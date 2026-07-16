from fastapi import APIRouter, Depends, HTTPException, Query, Request
from typing import Optional
from sqlalchemy.orm import Session
from pydantic import BaseModel
from app.db.session import get_db
from app.models.user import User
from app.models.system import AdminLog

from app.api.deps import get_admin_user
from app.core.security import get_password_hash
from app.services.settings_service import settings_service
import uuid

router = APIRouter(prefix='/admin', tags=['Admin'])

def _log(db, admin_id, action, target='', detail='', ip=''):
    """记录管理员操作日志"""
    log = AdminLog(id=uuid.uuid4(), admin_id=uuid.UUID(admin_id) if admin_id else None,
                   action=action, target=target, detail=detail, ip=ip)
    db.add(log)
    db.commit()

class CreateAdminReq(BaseModel):
    username: str
    password: str
    role: str = 'admin'

class UpdateRoleReq(BaseModel):
    role: str

class SettingsUpdate(BaseModel):
    scenic_name: Optional[str] = None
    business_hours: Optional[str] = None
    welcome_message: Optional[str] = None
    maintenance_mode: Optional[bool] = None
    language: Optional[str] = None

@router.get('/users')
async def list_admins(page: int = Query(1), size: int = Query(20), admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    users = db.query(User).filter(User.role.in_(['admin', 'super_admin'])).all()
    items = [{'id': str(u.id), 'username': u.nickname, 'role': u.role, 'last_login': u.updated_at} for u in users]
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'items': items}}

@router.post('/users', status_code=201)
async def create_admin(req: CreateAdminReq, request: Request, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    if db.query(User).filter(User.nickname == req.username).first():
        raise HTTPException(status_code=400, detail={'code': 40003, 'success': False, 'message': 'Username exists'})
    user = User(id=uuid.uuid4(), nickname=req.username, password_hash=get_password_hash(req.password), role=req.role)
    db.add(user)
    db.commit()
    # 记录创建管理员操作
    ip = request.headers.get('x-forwarded-for', request.client.host if request.client else '')
    _log(db, str(admin.id), 'admin.create', target=req.username, detail=f'创建管理员: {req.username}', ip=ip)
    return {'code': 0, 'success': True, 'message': 'Created'}

@router.put('/users/{user_id}/role')
async def update_role(user_id: str, req: UpdateRoleReq, request: Request, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    user = db.query(User).filter(User.id == uuid.UUID(user_id)).first()
    if not user:
        raise HTTPException(status_code=404, detail={'code': 40401, 'success': False, 'message': 'User not found'})
    user.role = req.role
    db.commit()
    ip = request.headers.get('x-forwarded-for', request.client.host if request.client else '')
    _log(db, str(admin.id), 'admin.update_role', target=user_id, detail=f'角色变更为: {req.role}', ip=ip)
    return {'code': 0, 'success': True, 'message': 'Updated'}

@router.get('/logs')
async def list_logs(page: int = Query(1), size: int = Query(20),
                    start_date: Optional[str] = None, end_date: Optional[str] = None,
                    action_type: Optional[str] = None,
                    admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    """查询操作日志，支持分页、日期范围、操作类型筛选"""
    q = db.query(AdminLog)
    if start_date:
        q = q.filter(AdminLog.created_at >= start_date)
    if end_date:
        q = q.filter(AdminLog.created_at <= end_date)
    if action_type:
        q = q.filter(AdminLog.action == action_type)
    items = q.order_by(AdminLog.created_at.desc()).offset((page-1)*size).limit(size).all()
    return {
        'code': 0, 'success': True, 'message': 'OK',
        'data': {
            'items': [{
                'admin_id': str(log.admin_id) if log.admin_id else '',
                'action': log.action, 'target': log.target,
                'detail': log.detail, 'ip': log.ip,
                'created_at': log.created_at
            } for log in items]
        }
    }

@router.get('/settings')
async def get_settings(admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    settings = settings_service.get_all(db)
    return {'code': 0, 'success': True, 'message': 'OK', 'data': settings}

@router.put('/settings')
async def update_settings(req: SettingsUpdate, request: Request, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    updated = settings_service.update(db, req.model_dump(exclude_unset=True))
    ip = request.headers.get('x-forwarded-for', request.client.host if request.client else '')
    _log(db, str(admin.id), 'admin.update_settings', detail=str(req.model_dump(exclude_unset=True)), ip=ip)
    return {'code': 0, 'success': True, 'message': 'Updated', 'data': updated}
