# Throttl console

Operator UI for the distributed rate limiter. It is independently buildable and is not part of the limiter engine.

```bash
npm install
npm run dev
```

The Vite app is on `http://localhost:5173`. API calls go to `http://127.0.0.1:8080` (`VITE_API_BASE_URL`). The limiter must be running on port 8080.
