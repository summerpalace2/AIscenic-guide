from fastapi import APIRouter, Depends
from pydantic import BaseModel
from typing import Optional
from app.api.deps import get_admin_user
from app.models.user import User
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.services.dh_config_service import dh_config_service

router = APIRouter(prefix='/admin/digital-human', tags=['DigitalHuman'])

class DHUpdate(BaseModel):
    appearance: Optional[dict] = None; voice: Optional[dict] = None; emotion_style: Optional[str] = None

@router.get('')
async def get_config(admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    config = dh_config_service.get_config(db)
    return {'code': 0, 'success': True, 'message': 'OK', 'data': config}

@router.put('')
async def update_config(req: DHUpdate, admin: User = Depends(get_admin_user), db: Session = Depends(get_db)):
    updated = dh_config_service.update_config(db, req.model_dump(exclude_unset=True))
    return {'code': 0, 'success': True, 'message': 'Updated', 'data': updated}

@router.get('/voices')
async def list_voices(admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': [{'voice_id': 'voice_warm_01', 'name': 'Warm Female', 'gender': 'female', 'preview_url': ''}, {'voice_id': 'voice_lively_01', 'name': 'Lively Girl', 'gender': 'female', 'preview_url': ''}, {'voice_id': 'voice_professional_01', 'name': 'Pro Male', 'gender': 'male', 'preview_url': ''}]}

@router.get('/appearances')
async def list_appearances(admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': [{'model_id': 'model_female_01', 'name': 'Traditional', 'preview_url': '', 'outfit': 'traditional', 'hairstyle': 'long'}, {'model_id': 'model_male_01', 'name': 'Scholar', 'preview_url': '', 'outfit': 'scholar', 'hairstyle': 'short'}]}
