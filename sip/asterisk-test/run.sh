#!/bin/sh
# Starts the Asterisk the SIP client tests talk to: ws://localhost:8088/ws,
# extensions 1001/1002 (plain RTP) and 1003 (WebRTC), see pjsip.conf.
set -e
cd "$(dirname "$0")"
docker rm -f h2o-asterisk-test >/dev/null 2>&1 || true
docker run -d --name h2o-asterisk-test --network host \
  $(for f in http.conf pjsip.conf extensions.conf logger.conf modules.conf; do echo -v "$PWD/$f:/etc/asterisk/$f:ro"; done) \
  andrius/asterisk:latest >/dev/null
for i in $(seq 1 30); do
  docker exec h2o-asterisk-test asterisk -rx "pjsip show transports" 2>/dev/null | grep -q transport-ws && exit 0
  sleep 1
done
echo "Asterisk did not start" >&2
docker logs h2o-asterisk-test | tail -30 >&2
exit 1
