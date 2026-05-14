#!/bin/sh
set -e

echo "[entrypoint] alembic upgrade head..."
alembic upgrade head

echo "[entrypoint] seed api_source..."
python scripts/seed_real_estate_source.py
python scripts/seed_law_source.py
python scripts/seed_jobs_source.py
python scripts/seed_auction_source.py

echo "[entrypoint] starting mcp server..."
exec python -m mcp_server
