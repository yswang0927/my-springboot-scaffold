class X2tConverter {
  constructor() {
    this.worker = null;
    this.initPromise = null;
    this.messageId = 0;
    this.pendingMessages = new Map();
    /**
     * Handle worker response messages
     */
    this.handleWorkerMessage = (event) => {
      const { id, type, payload, error } = event.data;
      if (type === "ready") {
        console.log("[X2tConverter] Worker ready");
        return;
      }
      const pending = this.pendingMessages.get(id);
      if (!pending) return;
      this.pendingMessages.delete(id);
      if (type === "error") {
        pending.reject(new Error(error || "Unknown worker error"));
      } else {
        pending.resolve(payload);
      }
    };
    /**
     * Handle worker errors
     */
    this.handleWorkerError = (error) => {
      console.error("[X2tConverter] Worker error:", error);
      for (const [id, pending] of this.pendingMessages) {
        pending.reject(new Error(`Worker error: ${error.message}`));
        this.pendingMessages.delete(id);
      }
    };
    if (globalThis.Worker) {
      this.init();
    }
  }
  /**
   * Get next unique message ID
   */
  getNextId() {
    return ++this.messageId;
  }
  /**
   * Send message to worker and wait for response
   */
  sendMessage(type, payload) {
    return new Promise((resolve, reject) => {
      if (!this.worker) {
        reject(new Error("Worker not initialized"));
        return;
      }
      const id = this.getNextId();
      this.pendingMessages.set(id, { resolve, reject });
      if (type === "convert" && payload?.data instanceof ArrayBuffer) {
        this.worker.postMessage({ id, type, payload }, [payload.data]);
      } else {
        this.worker.postMessage({ id, type, payload });
      }
    });
  }
  /**
   * Initialize the worker (automatically called on construction)
   */
  init() {
    if (this.initPromise) {
      return this.initPromise;
    }
    this.initPromise = new Promise((resolve, reject) => {
      try {
        this.worker = new Worker(new URL("./x2t.worker.js", import.meta.url));
        this.worker.onmessage = this.handleWorkerMessage;
        this.worker.onerror = this.handleWorkerError;
        console.log("[X2tConverter] Worker created");
        resolve();
      } catch (err) {
        this.initPromise = null;
        reject(err);
      }
    });
    return this.initPromise;
  }
  /**
   * Convert document from one format to another
   */
  async convert({
    data,
    fileFrom,
    fileTo,
    media,
    fonts,
    themes
  }) {
    await this.init();
    const cloneMap = (map) => {
      if (!map) return void 0;
      return Object.fromEntries(
        Object.entries(map).map(([key, value]) => [key, value.slice(0)])
      );
    };
    const dataClone = data.slice(0);
    const payload = {
      data: dataClone,
      fileFrom,
      fileTo,
      media: cloneMap(media),
      fonts: cloneMap(fonts),
      themes: cloneMap(themes)
    };
    return this.sendMessage("convert", payload);
  }
  /**
   * Terminate the worker and release resources
   */
  terminate() {
    if (this.worker) {
      for (const [id, pending] of this.pendingMessages) {
        pending.reject(new Error("Worker terminated"));
        this.pendingMessages.delete(id);
      }
      this.worker.terminate();
      this.worker = null;
      this.initPromise = null;
      console.log("[X2tConverter] Worker terminated");
    }
  }
  /**
   * Check if worker is initialized
   */
  get isInitialized() {
    return this.worker !== null && this.initPromise !== null;
  }
}
const converter = new X2tConverter();
export {
  X2tConverter,
  converter
};
