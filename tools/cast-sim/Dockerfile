# cast-sim: build with the REPO ROOT as context so the real receiver/ is baked in:
#   docker build -f tools/cast-sim/Dockerfile -t cast-sim:local .
FROM node:22-slim@sha256:de170b39439eb0e5a3444ad7d60b015614a381e10fecae2e30ec9e735122f38d

RUN apt-get update \
 && apt-get install -y --no-install-recommends chromium openssl ca-certificates fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/cast-sim
COPY tools/cast-sim/package.json tools/cast-sim/package-lock.json ./tools/cast-sim/
RUN cd tools/cast-sim && npm ci --omit=dev --ignore-scripts --no-audit --no-fund
COPY tools/cast-sim ./tools/cast-sim
COPY receiver ./receiver

# Unprivileged runtime user; run.sh additionally passes --user <host uid> so the
# report volume is owned by you. HOME/Chromium profile live in tmpfs (/tmp).
RUN useradd --system --uid 10001 --home-dir /tmp sim
USER sim
ENV HOME=/tmp CHROME_PATH=/usr/bin/chromium CAST_SIM_OUT=/out CAST_SIM_RECEIVER_DIR=/opt/cast-sim/receiver
WORKDIR /opt/cast-sim/tools/cast-sim
ENTRYPOINT ["node", "server.mjs"]
