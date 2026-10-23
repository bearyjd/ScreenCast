// Reject if `promise` has not settled within `ms` (the timer is cleared once settled).
export function withTimeout(promise, ms, label) {
  let timer;
  const limit = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms);
  });
  return Promise.race([promise, limit]).finally(() => clearTimeout(timer));
}
