export default {
  async fetch(request, env) {
    const incoming = new URL(request.url);
    const target = new URL(incoming.pathname + incoming.search, env.ORIGIN);
    const headers = new Headers(request.headers);
    headers.set("host", target.host);
    return fetch(new Request(target, {
      method: request.method,
      headers,
      body: request.body,
      redirect: "manual",
    }));
  },
};
