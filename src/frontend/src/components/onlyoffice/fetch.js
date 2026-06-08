function createFetchProxy(target = globalThis.fetch) {
  const middlewares = [];
  const BaseFetch = typeof target === "function" ? target : target.fetch.bind(target);

  // 单独处理下一些特殊的请求, 动态添加 '/assets/onlyoffice/' 前缀
  const BASE_URL_PREFIX = (window.APP_BASE_URL||"") + "/assets/onlyoffice";
  const SPEC_URLS = {
    "/themes.json": BASE_URL_PREFIX,
    "/plugins.json": BASE_URL_PREFIX,
    "/resources/numbering/numbering-lists.json": BASE_URL_PREFIX + "/web-apps/apps/documenteditor/main",
    "/resources/numbering/multilevel-lists.json": BASE_URL_PREFIX + "/web-apps/apps/documenteditor/main",
    "/common/main/resources/alphabetletters/alphabetletters.json": BASE_URL_PREFIX + "/web-apps/apps",
    "/common/main/resources/alphabetletters/qwertyletters.json": BASE_URL_PREFIX + "/web-apps/apps",
  };

  const proxy = (async (input, init) => {
    // 单独处理下一些特殊的请求, 动态添加 '/assets/onlyoffice/' 前缀
    let url = typeof input === "string" ? input : input.url;
    try {
      const u = new URL(url, location.origin);
      if (u.origin === location.origin) {
        if (SPEC_URLS[u.pathname]) {
          const newUrl = SPEC_URLS[u.pathname] + u.pathname + u.search;
          if (typeof input === "string") {
            input = newUrl;
          } else {
            input = new Request(newUrl, input);
          }
        }

      }
    } catch (e) { }

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
