from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field

class RegisterRequest(BaseModel):
    phone: str = Field(..., pattern=r"^\d{11}$")
    password: str = Field(..., min_length=6)
    nickname: Optional[str] = None

class LoginRequest(BaseModel):
    phone: str
    password: str

class AdminLoginRequest(BaseModel):
    username: str
    password: str

class TokenRefreshRequest(BaseModel):
    refresh_token: str

class AuthResponse(BaseModel):
    user_id: str
    token: str
    refresh_token: Optional[str] = None
    expires_in: int = 3600
    role: Optional[str] = "tourist"

class UserResponse(BaseModel):
    user_id: str
    nickname: str = ""
    avatar: str = ""
    role: str = "tourist"
    phone: str = ""
    interests: list[str] = []
    created_at: Optional[datetime] = None

class UpdateInterestsRequest(BaseModel):
    interests: list[str]

class UpdateProfileRequest(BaseModel):
    nickname: Optional[str] = None
    avatar: Optional[str] = None
