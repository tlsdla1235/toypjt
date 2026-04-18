## Local development

### 인프라 기동
\```bash
docker compose -f docker-compose.local.yml up -d
\```

### 상태 확인
\```bash
docker compose -f docker-compose.local.yml ps
\```

### 완전 초기화 (볼륨 포함)
\```bash
docker compose -f docker-compose.local.yml down -v
\```