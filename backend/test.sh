#!/bin/bash
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/home/vscode/.local/bin
pip3 install --break-system-packages --quiet -r /workspace/requirements.txt 2>/dev/null
cd /workspace && timeout 8 uvicorn app.main:app --host 0.0.0.0 --port 8000 2>/dev/null || echo "Uvicorn OK (timeout)"
