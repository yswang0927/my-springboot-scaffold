import { converter } from "./x2t";
import { AscSaveTypes } from "./types";
import { emptyDocx, emptyPdf, emptyPptx, emptyXlsx } from "./empty";
import { getDocumentType, getFileExt } from "./utils";
import { allPlugins, featuredPlugins, getPluginsData } from "./plugins";

function mergeBuffers(buffers) {
  const totalLength = buffers.reduce((acc, buffer) => acc + buffer.length, 0);
  const mergedBuffer = new Uint8Array(totalLength);
  let offset = 0;
  for (const buffer of buffers) {
    mergedBuffer.set(buffer, offset);
    offset += buffer.length;
  }
  return mergedBuffer;
}

function randomId() {
  return Math.random().toString(36).substring(2, 9);
}

function getUrl(data, type) {
  const blob = new Blob([data], {
    type: type || "application/octet-stream"
  });
  return URL.createObjectURL(blob);
}

const ONLYOFFICE_VERSION = "9.2.1";

class EditorServer {
  constructor(options = {}) {
    this.id = "";
    this.socket = null;
    this.sessionId = "session-id";
    this.user = {
      id: "uid",
      name: "Me"
    };
    this.participants = [];
    this.syncChangesIndex = 0;
    this.loadPromise = null;
    this.file = null;
    this.fileType = "docx";
    this.title = "";
    this.fsMap = new Map();
    this.urlsMap = new Map();
    this.downloadId = "";
    this.downloadParts = [];
    this.options = {};
    this.options = options;
    this.send = this.send.bind(this);
    this.handleConnect = this.handleConnect.bind(this);
    this.handleMessage = this.handleMessage.bind(this);
  }

  async open(file, { fileType, fileName } = {}) {
    const title = fileName || file.name;
    this.fileType = fileType || getFileExt(file.name) || "docx";
    const documentType = getDocumentType(this.fileType);
    this.id = randomId();
    this.file = file;
    this.title = title;
    const buffer = await file.arrayBuffer();
    this.loadPromise = this.loadDocument(buffer, this.fileType);
    return {
      id: this.id,
      documentType: documentType
    };
  }

  openNew(fileType) {
    this.fileType = fileType || "docx";
    this.id = this.id || randomId();
    this.title = "New Document";
    const documentType = getDocumentType(this.fileType);
    let binData = null;

    switch (documentType) {
      case "word":
        binData = Uint8Array.from(emptyDocx, (v) => v.charCodeAt(0));
        break;
      case "cell":
        binData = Uint8Array.from(emptyXlsx, (v) => v.charCodeAt(0));
        break;
      case "slide":
        binData = Uint8Array.from(emptyPptx, (v) => v.charCodeAt(0));
        break;
      case "pdf":
        binData = Uint8Array.from(emptyPdf, (v) => v.charCodeAt(0));
        break;
    }

    if (!binData) {
      throw new Error("Failed to create new document");
    }
    this.fsMap.set("Editor.bin", binData);
    this.urlsMap.set("Editor.bin", getUrl(binData));

    return {
      id: this.id,
      documentType: documentType
    };
  }

  async openUrl(url, { fileType, fileName } = {}) {
    const title = fileName || url.split("/").pop() || "Document";
    this.fileType = fileType || getFileExt(title) || "docx";
    const documentType = getDocumentType(this.fileType);
    this.id = randomId();
    this.title = title;
    const buffer = () => fetch(url).then((res) => res.arrayBuffer());
    this.loadPromise = this.loadDocument(buffer, this.fileType);
    return {
      id: this.id,
      documentType: documentType
    };
  }

  getDocument() {
    if (!this.id) {
      this.openNew();
    }

    return {
      fileType: this.fileType,
      key: this.id,
      title: this.title,
      url: "/" + this.id
    };
  }

  getUser() {
    return this.user;
  }

  async loadDocument(buffer, fileType) {
    if (typeof buffer == "function") {
      buffer = await buffer();
    }

    let output = null;
    let media = {};
    if (fileType == "pdf") {
      output = new Uint8Array(buffer);
    } else {
      const result = await converter.convert({
        data: buffer,
        fileFrom: "doc." + fileType,
        fileTo: "Editor.bin"
      });
      output = result.output;
      media = result.media;
    }

    if (!output) {
      throw new Error("Failed to convert file");
    }

    if (this.urlsMap.size > 0) {
      this.urlsMap.forEach((url) => URL.revokeObjectURL(url));
    }

    this.fsMap.set("Editor.bin", output);
    this.urlsMap.set("Editor.bin", getUrl(output));

    for (const name in media) {
      this.addMedia(name, media[name]);
    }
  }

  addMedia(name, data) {
    const pathname = "media/" + name;
    const url = getUrl(data);
    this.fsMap.set(pathname, data);
    this.urlsMap.set(pathname, url);
    return url;
  }

  handleConnect({ socket }) {
    console.log("connect: ", socket);
    this.socket = socket;
    const { send, sessionId } = this;
    this.participants = [
      {
        connectionId: this.sessionId,
        encrypted: false,
        id: this.user.id,
        idOriginal: this.user.id,
        indexUser: 1,
        isCloseCoAuthoring: false,
        isLiveViewer: false,
        username: this.user.name,
        view: false
      }
    ];
    socket.server.on("message", this.handleMessage);
    send({
      maxPayload: 1e8,
      pingInterval: 25e3,
      pingTimeout: 2e4,
      sid: sessionId,
      upgrades: []
    });
    send({
      type: "license",
      license: {
        type: 3,
        buildNumber: 8,
        buildVersion: ONLYOFFICE_VERSION,
        light: false,
        mode: 0,
        rights: 1,
        protectionSupport: true,
        isAnonymousSupport: true,
        liveViewerSupport: true,
        branding: false,
        customization: true,
        advancedApi: false
      }
    });
  }
  handleDisconnect({ socket }) {
    console.log("disconnect: ", socket);
    this.socket = null;
  }
  send(msg) {
    if (!this.socket) {
      console.error("Socket is not connected");
      return;
    }
    this.socket.server.emit("message", msg);
  }
  async handleMessage(msg, ...args) {
    console.log("[msg]: ", msg, args);
    const { send, sessionId, participants, user } = this;
    switch (msg.type) {
      case "auth":
        const changes = [];
        send({
          type: "authChanges",
          changes
        });
        send({
          type: "auth",
          result: 1,
          sessionId,
          participants,
          locks: [],
          //   changes: changes,
          //   changesIndex: 0,
          indexUser: 1,
          buildVersion: ONLYOFFICE_VERSION,
          buildNumber: 9,
          licenseType: 3,
          editorType: 2,
          mode: "edit",
          permissions: {
            comment: true,
            chat: true,
            download: true,
            edit: true,
            fillForms: false,
            modifyFilter: true,
            protect: true,
            print: true,
            review: false,
            copy: true
          }
        });
        try {
          if (this.loadPromise) {
            await this.loadPromise;
          }
          send({
            type: "documentOpen",
            data: {
              type: "open",
              status: "ok",
              data: {
                ...Object.fromEntries(this.urlsMap)
              }
            }
          });
        } catch (err) {
          console.error(err);
          send({
            type: "documentOpen",
            data: {
              type: "open",
              status: "ok",
              data: {
                "Editor.bin": ""
              }
            }
          });
        }
        break;
      case "isSaveLock":
        send({
          type: "saveLock",
          saveLock: false
        });
        break;
      case "saveChanges":
        send({
          type: "unSaveLock",
          index: -1,
          syncChangesIndex: ++this.syncChangesIndex,
          time: +new Date()
        });
        break;
      case "getLock":
        send({
          type: "getLock",
          locks: {
            [msg.block]: {
              time: +new Date(),
              user: user?.id,
              block: msg.block
            }
          }
        });
        send({
          type: "releaseLock",
          locks: {
            [msg.block]: {
              time: +new Date(),
              user: user?.id,
              block: msg.block
            }
          }
        });
        break;
    }
  }
  async handleRequest(req) {
    const u = new URL(req.url);
    const { id: key, send } = this;
    if (u.pathname.endsWith("/downloadas/" + key)) {
      const cmd = JSON.parse(u.searchParams.get("cmd") || "{}");
      const buffer = await req.arrayBuffer();
      console.log("downloadAs -> ", cmd, buffer);
      const fileTo = "doc." + cmd.title.split(".").pop();
      let formatTo = cmd.outputformat;
      if (!formatTo && fileTo.endsWith(".pdf")) {
        formatTo = 513;
      }

      const download = async () => {
        const input = mergeBuffers(this.downloadParts);
        let fileFrom = "from.bin";
        if (cmd.format == "pdf") {
          fileFrom = "from.pdf";
        }

        let { output } = await converter.convert({
          data: input.buffer,
          fileFrom,
          fileTo,
          formatTo,
          media: Object.fromEntries(this.fsMap)
        });

        if (!output && cmd.format == "pdf") {
          output = input;
        }

        if (!output) {
          console.error("Conversion failed");
          return { status: "error" };
        }

        const blob = new Blob([new Uint8Array(output)]);
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = cmd.title || "test.docx";
        a.click();
        URL.revokeObjectURL(url);
        return { status: "ok" };
      };

      let result = {
        status: "ok"
      };

      switch (cmd.savetype) {
        case AscSaveTypes.PartStart:
          this.downloadId = "_" + Math.round(Math.random() * 1e3);
          this.downloadParts = [new Uint8Array(buffer)];
          break;
        case AscSaveTypes.Part:
          this.downloadParts.push(new Uint8Array(buffer));
          break;
        case AscSaveTypes.Complete:
          this.downloadParts.push(new Uint8Array(buffer));
          result = await download();
          this.downloadParts = [];
          break;
        case AscSaveTypes.CompleteAll:
          this.downloadId = "_" + Math.round(Math.random() * 1e3);
          this.downloadParts = [new Uint8Array(buffer)];
          result = await download();
          this.downloadParts = [];
          break;
      }

      setTimeout(() => {
        send({
          type: "documentOpen",
          data: {
            type: "save",
            // status: "ok",
            status: result.status,
            data: "data:,",
            filetype: "pptx"
          }
        });
      }, 100);

      return Response.json({
        status: result.status,
        type: "save",
        data: this.downloadId
      });
    }

    if (u.pathname.endsWith("/upload/" + key)) {
      const buffer = await req.arrayBuffer();
      const data = new Uint8Array(buffer);
      const filename = Date.now() + ".png";
      const pathname = "media/" + filename;
      const url = this.addMedia(filename, data);
      return Response.json({ [pathname]: url });
    }

    if (u.pathname == "/plugins.json") {
      const state = this.options.getState?.();
      if (state?.plugins == "none") {
        return Response.json({ url: "", pluginsData: [], autostart: [] });
      }
      if (state?.plugins == "all") {
        return Response.json(getPluginsData(allPlugins));
      }
      return Response.json(getPluginsData(featuredPlugins));
    }
    return null;
  }
}

export { EditorServer };
