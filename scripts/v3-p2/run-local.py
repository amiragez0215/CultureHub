import argparse
from contextlib import ExitStack
import importlib.util
import os
from pathlib import Path

p1_spec = importlib.util.spec_from_file_location("live_p1", Path(__file__).resolve().parents[1] / "v3-p1" / "run-local.py")
live_p1 = importlib.util.module_from_spec(p1_spec)
p1_spec.loader.exec_module(live_p1)

conf, _ = live_p1.setup()
root = live_p1.ROOT
live_p1.root_sql("USE core_db;\n" + (root / "deploy/migrations/20261111-v3-booking.sql").read_text(encoding="utf-8"))
spec = importlib.util.spec_from_file_location("p2_smoke", root / "scripts/v3-p2/smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
env = {
    **os.environ, **conf, "SPRING_PROFILES_ACTIVE": "v3-p2", "V3_DISCOVERY_ENABLED": "false",
    "DB_URL": live_p1.jdbc("core_db"), "REDIS_HOST": "127.0.0.1", "REDIS_PORT": "16379",
    "RABBIT_HOST": "127.0.0.1", "RABBIT_PORT": "5673", "RABBIT_USER": "guest",
    "RABBIT_PASSWORD": "guest", "RABBIT_VHOST": "culturehub_v3p1_audit",
    "JOBS_ENABLED": "true", "XXL_JOB_ADMIN": "", "DEMO_PASSWORD": "",
    "AI_CHAT_PROVIDER": "none", "AI_EMBEDDING_PROVIDER": "none", "AI_VECTORSTORE": "none",
    "V3_BOOKING_RECOVERY_DELAY_MS": "60000", "V3_BOOKING_RECONCILIATION_DELAY_MS": "60000",
}
os.environ["XXL_JOB_TOKEN"] = conf["XXL_JOB_TOKEN"]
with ExitStack() as stack:
    core = live_p1.spawn(stack, "p2-core", "culturehub-api", 18110, env)
    live_p1.ready("p2-core", core, 18110, "/actuator/health/readiness")
    smoke.run(argparse.Namespace(core="http://127.0.0.1:18110",
                                 executor="http://127.0.0.1:9999", mysql=live_p1.MYSQL,
                                 mysql_port=13306, faults=True))
