// The projected service account token the agent hub accepts from the gateway, on a VM's web
// routes alone. The kubelet rotates the file, so it is read for each request; absent outside a
// cluster, no token is sent and the hub refuses.
import fs from 'fs';

const TOKEN = '/var/run/secrets/workspaced-ai/agent-hub/token';

function token() {
  try {
    return fs.readFileSync(TOKEN, 'utf8').trim();
  } catch (e) {
    return '';
  }
}

export default { token };
