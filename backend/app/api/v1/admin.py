from fastapi import APIRouter, Depends, HTTPException, Query
from typing import Optional
from sqlalchemy.orm import Session
from pydantic import BaseModel
from app.db.session import get_db
from app.models.user import User
from app.api.deps import get_admin_user
from app.core.security import get_password_hash
import uuid

router = APIRouter(prefix='/admin', tags=['Admin'])

class CreateAdminReq(BaseModel):
    username: str; password: str; role: str = 'admin'

class UpdateRoleReq(BaseModel):
    role: str

class SettingsUpdate(BaseModel):
    scenic_name: Optional[str] = None; business_hours: Optional[str] = None
    welcome_message: Optional[str] = None; maintenance_mode: Optional[bool] = None; language: Optional[str] = None

@router.get('/users')
async def list_admins(page: int = Query(1), size: int = Query(20), admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    users = db.query(User).filter(User.role.in_(['admin', 'super_admin'])).all()
    items = [{'id': str(u.id), 'username': u.nickname, 'role': u.role, 'last_login': u.updated_at} for u in users]
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'items': items}}

@router.post('/users', status_code=201)
async def create_admin(req: CreateAdminReq, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    if db.query(User).filter(User.nickname == req.username).first():
        raise HTTPException(status_code=400, detail={'code': 40003, 'success': False, 'message': 'Username exists'})
    user = User(id=uuid.uuid4(), nickname=req.username, password_hash=get_password_hash(req.password), role=req.role)
    db.add(user); db.commit()
    return {'code': 0, 'success': True, 'message': 'Created'}

@router.put('/users/{user_id}/role')
async def update_role(user_id: str, req: UpdateRoleReq, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    user = db.query(User).filter(User.id == user_id).first()
    if not user: raise HTTPException(status_code=404, detail={'code': 40401, 'success': False, 'message': 'User not found'})
    user.role = req.role; db.commit()
    return {'code': 0, 'success': True, 'message': 'Updated'}

@router.get('/logs')
async def list_logs(page: int = Query(1), size: int = Query(20), admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'items': []}}

_S = {'scenic_name': 'Lingshan', 'business_hours': '08:30-17:00', 'welcome_message': 'Welcome!', 'maintenance_mode': False, 'language': 'zh-CN'}

@router.get('/settings')
async def get_settings(admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': _S}

@router.put('/settings')
async def update_settings(req: SettingsUpdate, admin: User = Depends(get_admin_user)):
    for k, v in req.model_dump(exclude_unset=True).items():
        if v is not None: _S[k] = v
    return {'code': 0, 'success': True, 'message': 'Updated'}
