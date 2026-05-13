from fastapi import APIRouter, Depends, HTTPException, Query
from typing import Optional
from sqlalchemy.orm import Session
from app.db.session import get_db
from app.models.scenic_spot import ScenicSpot

router = APIRouter(prefix='/location', tags=['Location'])

@router.get('/scenic-spots')
async def list_spots(category: Optional[str] = None, keyword: Optional[str] = None, db: Session = Depends(get_db)):
    q = db.query(ScenicSpot)
    if category: q = q.filter(ScenicSpot.category == category)
    if keyword: q = q.filter(ScenicSpot.name.ilike(f'%{keyword}%'))
    spots = q.all()
    items = [{'id': str(s.id), 'name': s.name, 'category': s.category, 'description': s.description, 'longitude': s.longitude, 'latitude': s.latitude, 'audio_url': s.audio_url, 'tags': s.tags or []} for s in spots]
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'total': len(items), 'items': items}}

@router.get('/scenic-spots/{spot_id}')
async def get_spot(spot_id: str, db: Session = Depends(get_db)):
    s = db.query(ScenicSpot).filter(ScenicSpot.id == spot_id).first()
    if not s: raise HTTPException(status_code=404, detail={'code': 40404, 'success': False, 'message': 'Spot not found'})
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'id': str(s.id), 'name': s.name, 'category': s.category, 'description': s.description, 'longitude': s.longitude, 'latitude': s.latitude, 'audio_url': s.audio_url, 'tags': s.tags or []}}

@router.get('/nearby')
async def nearby_spots(longitude: float = Query(...), latitude: float = Query(...), radius: int = Query(500), db: Session = Depends(get_db)):
    spots = db.query(ScenicSpot).all()
    items = [{'id': str(s.id), 'name': s.name, 'category': s.category, 'description': s.description, 'longitude': s.longitude, 'latitude': s.latitude, 'distance': 0.0} for s in spots]
    return {'code': 0, 'success': True, 'message': 'OK', 'data': {'total': len(items), 'items': items}}
