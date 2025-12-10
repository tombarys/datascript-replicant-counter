#!/bin/bash
# Deploy script for DataScript Counter App

set -e  # Exit on error

SERVER="root@91.98.234.203"
BACKEND_DIR="/opt/counter-app"
FRONTEND_DIR="/var/www/counter-app"

echo "🚀 Deploying DataScript Counter App"
echo "===================================="

# 1. Build Frontend
echo "📦 Building frontend..."
cd frontend
npm run build
echo "✅ Frontend built"

# 2. Build Backend
echo "📦 Building backend..."
cd ../backend
clojure -X:uberjar
echo "✅ Backend built"

# 3. Deploy Frontend
echo "🌐 Deploying frontend..."
cd ../frontend
scp public/js/main.js "$SERVER:$FRONTEND_DIR/js/"
scp public/index.html "$SERVER:$FRONTEND_DIR/"
echo "✅ Frontend deployed"

# 4. Deploy Backend
echo "🖥️  Deploying backend..."
cd ../backend
ssh "$SERVER" "mkdir -p $BACKEND_DIR"
scp counter-app.jar "$SERVER:$BACKEND_DIR/"
scp src/counter/core.clj "$SERVER:$BACKEND_DIR/src/counter/"
scp deps.edn "$SERVER:$BACKEND_DIR/"
echo "✅ Backend deployed"

# 5. Restart Backend
echo "🔄 Restarting backend service..."
ssh "$SERVER" "systemctl restart counter-app"
echo "✅ Backend restarted"

echo ""
echo "🎉 Deployment complete!"
echo "Frontend: http://91.98.234.203"
echo "Backend API: http://91.98.234.203/api/counter"
