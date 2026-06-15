// 注意这里的 `/assets/onlyoffice` 前缀路径根据结构自行修改
const BASE_URL = self.location.origin + "/assets/onlyoffice/x2t/";

const AvsFileType = {
  AVS_FILE_DOCUMENT_DOC: 66,
  AVS_FILE_CROSSPLATFORM_PDFA: 521
};

let x2t = null;
let initPromise = null;

async function initX2t() {
  if (x2t) return;
  const scriptUrl = BASE_URL + "x2t.js";
  Object.assign(self, {
    __filename: BASE_URL
  });

  importScripts(scriptUrl);

  x2t = self.Module;
  await new Promise((resolve) => {
    x2t.onRuntimeInitialized = () => resolve();
  });

  try {
    x2t.FS.mkdir("/working");
    x2t.FS.mkdir("/working/media");
    x2t.FS.mkdir("/working/fonts");
    x2t.FS.mkdir("/working/themes");
  } catch (err) {
    console.error("[x2t.worker] mkdir error:", err);
  }
  console.log("[x2t.worker] Initialized successfully");
}

async function ensureInit() {
  if (!initPromise) {
    initPromise = initX2t();
  }
  return initPromise;
}

ensureInit().catch((err) => {
  console.error("[x2t.worker] Auto-init failed:", err);
});

function cleanupFiles(files) {
  for (const file of files) {
    try {
      x2t.FS.unlink(file);
    } catch (err) {
      console.error(err);
    }
  }
  cleanMedia();
}

function cleanMedia() {
  try {
    const mediaFiles = x2t.FS.readdir("/working/media/");
    for (const file of mediaFiles) {
      if (file !== "." && file !== "..") {
        x2t.FS.unlink("/working/media/" + file);
      }
    }
  } catch (err) {
    console.error(err);
  }
}

function readMedia() {
  const media = {};
  try {
    const files = x2t.FS.readdir("/working/media/");
    for (const file of files) {
      if (file !== "." && file !== "..") {
        const fileData = x2t.FS.readFile("/working/media/" + file, {
          encoding: "binary"
        });
        media[file] = fileData;
      }
    }
  } catch (e) {
    console.error(e);
  }
  return media;
}

const xmlPath = "/working/params.xml";

function writeInputs({
  fileFrom,
  fileTo,
  formatFrom,
  formatTo,
  data,
  media
}) {
  const params = {
    m_sFileFrom: fileFrom,
    m_sThemeDir: "/working/themes",
    m_sFileTo: fileTo,
    m_nFormatFrom: formatFrom,
    m_nFormatTo: formatTo,
    m_bIsPDFA: formatTo === AvsFileType.AVS_FILE_CROSSPLATFORM_PDFA,
    m_bIsNoBase64: false,
    m_sFontDir: "/working/fonts/"
  };
  const content = Object.entries(params).filter(([k, v]) => v).reduce((a, [k, v]) => a + `<${k}>${v}</${k}>
`, "");
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<TaskQueueDataConvert
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xmlns:xsd="http://www.w3.org/2001/XMLSchema"
>
${content}
</TaskQueueDataConvert>`;
  x2t.FS.writeFile(xmlPath, xml);
  if (data) {
    x2t.FS.writeFile(fileFrom, new Uint8Array(data));
  }
  if (media) {
    cleanMedia();
    for (const [key, value] of Object.entries(media)) {
      try {
        x2t.FS.writeFile("/working/" + key, value);
      } catch (err) {
        console.error(key, err);
      }
    }
  }
}

async function convert({
  data,
  fileFrom,
  fileTo,
  formatFrom,
  formatTo,
  media,
  fonts,
  themes
}) {
  const fromPath = "/working/" + fileFrom;
  const toPath = "/working/" + fileTo;
  const files = [fromPath, toPath, xmlPath];

  writeInputs({
    fileFrom: fromPath,
    fileTo: toPath,
    formatFrom,
    formatTo,
    data,
    media
  });

  if (fileFrom.endsWith(".doc") || formatFrom == AvsFileType.AVS_FILE_DOCUMENT_DOC) {
    const viaPath = fromPath + ".docx";
    writeInputs({
      fileFrom: fromPath,
      fileTo: viaPath,
      data: null
    });
    x2t.ccall("main1", ["number"], ["string"], [xmlPath]);
    writeInputs({
      fileFrom: viaPath,
      fileTo: toPath,
      data: null
    });
    files.push(viaPath);
  }

  try {
    const pathInfo = x2t.FS.analyzePath(toPath);
    if (pathInfo.exists) {
      x2t.FS.unlink(toPath);
    }
  } catch (err) {
  }

  try {
    x2t.ccall("main1", ["number"], ["string"], [xmlPath]);
  } catch (e) {
    console.error("ccall", e);
  }

  let output = null;
  try {
    output = x2t.FS.readFile(toPath);
  } catch (e) {
    console.error(e);
  }

  const outputMedia = readMedia();
  setTimeout(() => {
    cleanupFiles(files);
  });

  return { output, media: outputMedia };
}

self.onmessage = async (event) => {
  const { id, type, payload } = event.data;
  try {
    switch (type) {
      case "convert": {
        await ensureInit();
        const result = await convert(payload);
        const transferables = [];
        if (result.output) {
          transferables.push(result.output.buffer);
        }
        Object.values(result.media).forEach(
          (m) => transferables.push(m.buffer)
        );
        self.postMessage(
          { id, type: "convert:done", payload: result },
          { transfer: transferables }
        );
        break;
      }
      default:
        self.postMessage({
          id,
          type: "error",
          error: `Unknown message type: ${type}`
        });
    }
  } catch (error) {
    self.postMessage({
      id,
      type: "error",
      error: error instanceof Error ? error.message : String(error)
    });
  }
};

self.postMessage({ type: "ready" });
