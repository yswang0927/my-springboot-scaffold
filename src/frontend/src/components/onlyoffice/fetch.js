function createFetchProxy(target = globalThis.fetch) {
  const middlewares = [];
  const BaseFetch = typeof target === "function" ? target : target.fetch.bind(target);
  const proxy = (async (input, init) => {
    let url = typeof input === "string" ? input : input.url;
    try {
      const u = new URL(url, location.origin);
      if (u.origin === location.origin) {
        if (u.pathname.startsWith("/common/") || u.pathname.startsWith("/sdkjs/") || u.pathname.startsWith("/web-apps/")) {
          const newUrl = location.origin + "/assets/onlyoffice" + u.pathname + u.search;
          if (typeof input === "string") {
            input = newUrl;
          } else {
            input = new Request(newUrl, input);
          }
        }
      }
    } catch (e) {}

    let request;
    try {
      request = new Request(input, init);
    } catch (e) {
      return BaseFetch(input, init);
    }
    try {
      for (const mw of middlewares) {
        const response = await mw(request.clone());
        if (response) {
          return response;
        }
      }
    } catch (err) {
      console.error("ProxyFetch middleware error:", err);
      return BaseFetch(request);
    }
    return BaseFetch(request);
  });
  proxy.use = (middleware) => {
    middlewares.push(middleware);
  };
  proxy.clearMiddlewares = () => {
    middlewares.length = 0;
  };
  return proxy;
}
export {
  createFetchProxy
};
