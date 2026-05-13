from pydantic_settings import BaseSettings
from functools import lru_cache

class Settings(BaseSettings):
    APP_NAME: str = 'AI Digital Human Scenic Guide'
    API_V1_PREFIX: str = '/api/v1'
    DATABASE_URL: str = 'postgresql://scenic:change_me@localhost:5432/scenic_guide'
    REDIS_HOST: str = 'localhost'
    REDIS_PORT: int = 6379
    REDIS_PASSWORD: str = ''
    REDIS_DB: int = 0
    REDIS_SSL: bool = False
    JWT_SECRET_KEY: str = 'scenic-guide-jwt-dev-key-change-in-production'
    JWT_ALGORITHM: str = 'HS256'
    JWT_ACCESS_TOKEN_EXPIRE_MINUTES: int = 60
    JWT_REFRESH_TOKEN_EXPIRE_DAYS: int = 7
    AI_SERVICE_URL: str = 'http://localhost:8081'
    MAX_UPLOAD_SIZE: int = 50 * 1024 * 1024

    class Config:
        env_file = '.env'
        env_file_encoding = 'utf-8'
        extra = 'ignore'

@lru_cache()
def get_settings() -> Settings:
    return Settings()
