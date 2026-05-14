"""
敏感词过滤依赖
在指定路由上作为 Depends 使用，不拦截中间件层级
"""
from fastapi import Request, HTTPException

DEFAULT_SENSITIVE_WORDS = [
    "暴力", "赌博", "毒品", "色情", "诈骗", "恐怖", "分裂",
    "反动", "邪教", "仇恨", "歧视", "枪支", "弹药", "凶器",
    "自杀", "自残", "fuck", "shit", "asshole", "bitch",
    "bastard", "idiot", "stupid", "kill", "murder", "threat"
]

async def check_sensitive_words(request: Request):
    """检查请求体是否包含敏感词"""
    body = await request.body()
    if body:
        text = body.decode('utf-8', errors='ignore').lower()
        for word in DEFAULT_SENSITIVE_WORDS:
            if word.lower() in text:
                raise HTTPException(status_code=403, detail={'code': 40303, 'success': False, 'message': '输入内容包含敏感词汇，请修改后重试'})
    return True
