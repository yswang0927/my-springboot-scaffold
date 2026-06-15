import { EventEmitter } from "eventemitter3";

class MockSocket {
  constructor(options = {}) {
    this.active = true;
    this.connected = false;
    this.disconnected = true;
    this.recovered = false;
    this.id = "";
    this.io = {
      setOpenToken: () => {},
      setSessionToken: () => {},
      on: () => {},
      reconnectionAttempts: () => {},
      reconnectionDelay: () => {},
      reconnectionDelayMax: () => {},
      timeout: () => {},
      transports: () => {},
      upgrade: () => {},
      upgradeTransport: () => {},
      upgradeTimeout: () => {}
    };
    this._clientEmitter = new EventEmitter();
    this._serverEmitter = new EventEmitter();
    this.server = {
      on: (event, listener) => {
        this._serverEmitter.on(event, listener);
      },
      off: (event, listener) => {
        this._serverEmitter.off(event, listener);
      },
      emit: (event, ...args) => {
        this._clientEmitter.emit(event, ...args);
      }
    };
    this._debug = options.debug ?? true;
    this.connect();
  }

  static {
    this._staticEmitter = new EventEmitter();
  }

  static on(event, listener) {
    MockSocket._staticEmitter.on(event, listener);
  }

  static off(event, listener) {
    MockSocket._staticEmitter.off(event, listener);
  }

  _log(...args) {
    if (this._debug) {
      console.log("[MockSocket]", ...args);
    }
  }

  open() {
    return this.connect();
  }

  compress() {
  }

  /**
   * Simulates connection establishment and generates a new Session ID.
   */
  connect() {
    this.connected = true;
    this.disconnected = false;
    this.id = Math.random().toString(36).slice(2);
    setTimeout(() => {
      this._trigger("connect");
      MockSocket._staticEmitter.emit("connect", { socket: this });
    }, 0);
    return this;
  }

  disconnect() {
    this.connected = false;
    this.disconnected = true;
    this._trigger("disconnect");
    MockSocket._staticEmitter.emit("disconnect", { socket: this });
    return this;
  }

  close() {
    return this.disconnect();
  }

  /**
   * Triggers local listeners (internal helper).
   * Used to simulate incoming server events.
   */
  _trigger(event, ...args) {
    this._log(`trigger event: ${event}`, ...args);
    this._clientEmitter.emit(event, ...args);
    return this;
  }

  // --- Client API ---
  /**
   * Registers a listener for an event from the server.
   */
  on(event, listener) {
    this._clientEmitter.on(event, listener);
    return this;
  }

  /**
   * Registers a one-time listener for an event from the server.
   */
  once(event, listener) {
    this._clientEmitter.once(event, listener);
    return this;
  }

  /**
   * Removes a listener for an event.
   */
  off(event, listener) {
    this._clientEmitter.off(event, listener);
    return this;
  }

  /**
   * Removes all listeners, or those of the specified event.
   */
  removeAllListeners(event) {
    this._clientEmitter.removeAllListeners(event);
    return this;
  }

  /**
   * Sends a message to the server using the 'message' event.
   * This is a shorthand for `emit('message', ...args)`.
   */
  send(...args) {
    if (!this.connected) return this;
    this.emit("message", ...args);
    return this;
  }

  /**
   * Sends a message to the server.
   * First tries global middlewares, then instance handler defined by `serverSideOn`.
   */
  emit(event, ...args) {
    this._log(`emit: ${event}`, ...args);
    if (!this.connected) return this;
    const processEmit = async () => {
      this._serverEmitter.emit(event, ...args);
    };
    setTimeout(() => processEmit(), 0);
    return this;
  }
}

function io(url, options) {
  return new MockSocket(options);
}

const ioWithStatics = io;
const socket_default = ioWithStatics;

export {
  MockSocket,
  socket_default as default,
  io
};
