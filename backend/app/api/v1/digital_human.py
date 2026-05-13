from fastapi import APIRouter, Depends
from pydantic import BaseModel
from typing import Optional
from app.api.deps import get_admin_user
from app.models.user import User

router = APIRouter(prefix='/admin/digital-human', tags=['DigitalHuman'])
CFG = {'appearance': {'model_id': 'model_female_01', 'outfit': 'traditional', 'hairstyle': 'long'}, 'voice': {'voice_id': 'voice_warm_01', 'speed': 1.0, 'pitch': 1.0, 'volume': 1.0}, 'emotion_style': 'friendly', 'preview_url': ''}

class DHUpdate(BaseModel):
    appearance: Optional[dict] = None; voice: Optional[dict] = None; emotion_style: Optional[str] = None

@router.get('')
async def get_config(admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': CFG}

@router.put('')
async def update_config(req: DHUpdate, admin: User = Depends(get_admin_user)):
    for k, v in req.model_dump(exclude_unset=True).items():
        if v is not None: CFG[k] = v
    return {'code': 0, 'success': True, 'message': 'Updated', 'data': CFG}

@router.get('/voices')
async def list_voices(admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': [{'voice_id': 'voice_warm_01', 'name': 'Warm Female', 'gender': 'female', 'preview_url': ''}, {'voice_id': 'voice_lively_01', 'name': 'Lively Girl', 'gender': 'female', 'preview_url': ''}, {'voice_id': 'voice_professional_01', 'name': 'Pro Male', 'gender': 'male', 'preview_url': ''}]}

@router.get('/appearances')
async def list_appearances(admin: User = Depends(get_admin_user)):
    return {'code': 0, 'success': True, 'message': 'OK', 'data': [{'model_id': 'model_female_01', 'name': 'Traditional', 'preview_url': '', 'outfit': 'traditional', 'hairstyle': 'long'}, {'model_id': 'model_male_01', 'name': 'Scholar', 'preview_url': '', 'outfit': 'scholar', 'hairstyle': 'short'}]}
