function createXHRProxy(BaseXHR = globalThis.XMLHttpRequest) {
  return class ProxyXMLHttpRequest extends BaseXHR {
    constructor() {
      super(...arguments);
      this._isMocked = false;
      this._requestMethod = "GET";
      this._requestUrl = "";
      this._requestHeaders = new Headers();
      this._requestBody = null;
    }
    static {
      this._middlewares = [];
    }
    /**
     * Register global middleware
     */
    static use(middleware) {
      this._middlewares.push(middleware);
    }
    /**
     * Clear all middleware
     */
    static clearMiddlewares() {
      this._middlewares = [];
    }
    open(method, url, async = true, username, password) {
      this._requestMethod = method;
      this._requestHeaders = new Headers();
      let urlStr = url.toString();
      try {
        const u = new URL(urlStr, location.origin);
        if (u.origin === location.origin) {
          if (u.pathname.startsWith("/common/") || u.pathname.startsWith("/sdkjs/") || u.pathname.startsWith("/web-apps/")) {
            urlStr = location.origin + "/assets/onlyoffice" + u.pathname + u.search;
          }
        }
      } catch (e) {}
      this._requestUrl = urlStr;
      this._isMocked = false;
      super.open(
        method,
        urlStr,
        async,
        username ?? void 0,
        password ?? void 0
      );
    }
    setRequestHeader(name, value) {
      this._requestHeaders.append(name, value);
      if (!this._isMocked) {
        super.setRequestHeader(name, value);
      }
    }
    send(body) {
      this._requestBody = body;
      this._tryMiddlewares().then((handled) => {
        if (!handled) {
          super.send(body);
        }
      }).catch((err) => {
        console.error("ProxyXMLHttpRequest middleware error:", err);
        super.send(body);
      });
    }
    async _tryMiddlewares() {
      let request;
      try {
        const reqInit = {
          method: this._requestMethod,
          headers: this._requestHeaders,
          body: this._requestBody,
          mode: "cors"
        };
        if (this.withCredentials) {
          reqInit.credentials = "include";
        }
        request = new Request(this._requestUrl, reqInit);
        console.log("ProxyXHR created request:", {
          url: this._requestUrl,
          method: request.method,
          hasBody: !!request.body,
          originalBody: this._requestBody
        });
      } catch (e) {
        return false;
      }
      for (const mw of ProxyXMLHttpRequest._middlewares) {
        const response = await mw(request.clone());
        if (response) {
          this._isMocked = true;
          await this._handleMockResponse(response);
          return true;
        }
      }
      return false;
    }
    async _handleMockResponse(response) {
      this.dispatchEvent(new ProgressEvent("loadstart"));
      Object.defineProperty(this, "readyState", {
        value: 2,
        writable: false,
        configurable: true
      });
      this.dispatchEvent(new Event("readystatechange"));
      Object.defineProperty(this, "readyState", {
        value: 3,
        writable: false,
        configurable: true
      });
      this.dispatchEvent(new Event("readystatechange"));
      try {
        let responseData;
        if (this.responseType === "json") {
          responseData = await response.json();
        } else if (this.responseType === "arraybuffer") {
          responseData = await response.arrayBuffer();
        } else if (this.responseType === "blob") {
          responseData = await response.blob();
        } else if (this.responseType === "document") {
          const text = await response.text();
          responseData = new DOMParser().parseFromString(text, "text/xml");
        } else {
          responseData = await response.text();
        }
        Object.defineProperty(this, "status", {
          value: response.status,
          writable: false,
          configurable: true
        });
        Object.defineProperty(this, "statusText", {
          value: response.statusText,
          writable: false,
          configurable: true
        });
        Object.defineProperty(this, "response", {
          value: responseData,
          writable: false,
          configurable: true
        });
        Object.defineProperty(this, "responseText", {
          value: typeof responseData === "string" ? responseData : JSON.stringify(responseData),
          writable: false,
          configurable: true
        });
        Object.defineProperty(this, "responseURL", {
          value: response.url,
          writable: false,
          configurable: true
        });
        this.dispatchEvent(
          new ProgressEvent("progress", {
            lengthComputable: true,
            loaded: 100,
            total: 100
          })
        );
        Object.defineProperty(this, "readyState", {
          value: 4,
          writable: false,
          configurable: true
        });
        this.dispatchEvent(new Event("readystatechange"));
        this.dispatchEvent(new ProgressEvent("load"));
        this.dispatchEvent(new ProgressEvent("loadend"));
      } catch (e) {
        console.error("ProxyXHR: error handling response", e);
        Object.defineProperty(this, "readyState", {
          value: 4,
          writable: false,
          configurable: true
        });
        this.dispatchEvent(new Event("readystatechange"));
        this.dispatchEvent(new ProgressEvent("error"));
        this.dispatchEvent(new ProgressEvent("loadend"));
      }
    }
  };
}
export {
  createXHRProxy
};
