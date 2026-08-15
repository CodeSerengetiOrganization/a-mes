# A-MES on K3s (staging / `ames-dev`)

Customer A MES stack. MySQL is already here; promote apps under this tree.

## NodePort block (frozen)

Reserved **30100–30109** for A-MES so API + UI stay continuous and clear of MMS (`30080–30082`) and mes-prod API (`30083`).

| Port | Service | Status |
|------|---------|--------|
| **30100** | mes-api | In use — [`mes-api/`](mes-api/) |
| **30101** | panel-registration-ui (PCB Loader) | Reserved — AS2-deploy-ui |
| **30102–30109** | future A-MES apps | Reserved |

Do not reuse `30083` for A-MES even if it frees up.

## Layout

| Path | Role |
|------|------|
| [`namespace.yaml`](namespace.yaml) | `ames-dev` |
| [`mysql/`](mysql/) | MySQL (already on cluster) |
| [`mes-api/`](mes-api/) | MES API promote + smoke |
