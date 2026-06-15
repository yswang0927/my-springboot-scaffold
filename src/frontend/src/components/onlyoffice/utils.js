function getFileExt(name) {
  const type = name.split(".").pop() || "";
  return type.toLowerCase();
}

function deepAssign(target, source) {
  const t = target;
  const s = source;

  if (typeof t != "object") {
    return s;
  }

  const result = Object.assign({}, t);
  for (const key of Object.keys(s)) {
    result[key] = deepAssign(t[key], s[key]);
  }
  return result;
}

function waitForEvent(element, eventType) {
  return new Promise((resolve) => {
    element.addEventListener(eventType, () => resolve(), { once: true });
  });
}

var AppType = ((AppType2) => {
  AppType2[AppType2["word"] = 1] = "word";
  AppType2[AppType2["cell"] = 2] = "cell";
  AppType2[AppType2["slide"] = 3] = "slide";
  AppType2[AppType2["draw"] = 4] = "draw";
  AppType2[AppType2["pdf"] = 5] = "pdf";
  return AppType2;
})(AppType || {});

const DOCUMENT_TYPE = {
  "Word": "word",
  "Cell": "cell",
  "Slide": "slide",
  "Draw": "draw",
  "Pdf": "pdf"
};

const docTypeMap = {
  // Document
  docx: 1 /* word */,
  doc: 1 /* word */,
  odt: 1 /* word */,
  rtf: 1 /* word */,
  txt: 1 /* word */,
  html: 1 /* word */,
  mht: 1 /* word */,
  epub: 1 /* word */,
  fb2: 1 /* word */,
  mobi: 1 /* word */,
  docm: 1 /* word */,
  dotx: 1 /* word */,
  dotm: 1 /* word */,
  oform: 1 /* word */,
  docxf: 1 /* word */,
  // Spreadsheet
  xlsx: 2 /* cell */,
  xls: 2 /* cell */,
  ods: 2 /* cell */,
  csv: 2 /* cell */,
  xlsm: 2 /* cell */,
  xltx: 2 /* cell */,
  xltm: 2 /* cell */,
  xlsb: 2 /* cell */,
  ots: 2 /* cell */,
  // Presentation
  pptx: 3 /* slide */,
  ppt: 3 /* slide */,
  odp: 3 /* slide */,
  ppsx: 3 /* slide */,
  pptm: 3 /* slide */,
  ppsm: 3 /* slide */,
  potx: 3 /* slide */,
  potm: 3 /* slide */,
  otp: 3 /* slide */,
  odg: 3 /* slide */,
  // Draw
  vsdx: 4 /* draw */,
  vssx: 4 /* draw */,
  vstx: 4 /* draw */,
  vsdm: 4 /* draw */,
  vssm: 4 /* draw */,
  vstm: 4 /* draw */,
  // PDF
  pdf: 5 /* pdf */
};

function getDocumentType(ext) {
  const code = docTypeMap[ext.toLowerCase()];
  const type = AppType[code];
  return type || DOCUMENT_TYPE.Word;
}

// 注意这里的 `/assets/onlyoffice` 访问前缀, 根据实际情况自行修改
const ONLYOFFICE_ROOT = (window.APP_BASE_URL || "") + "/assets/onlyoffice";
const ONLYOFFICE_API_JS = ONLYOFFICE_ROOT + "/web-apps/apps/api/documents/api.js";
const ONLYOFFICE_PRELOAD_HTML = ONLYOFFICE_ROOT + "/web-apps/apps/api/documents/preload.html";

export {
  ONLYOFFICE_ROOT,
  ONLYOFFICE_PRELOAD_HTML,
  ONLYOFFICE_API_JS,
  AppType,
  docTypeMap,
  getDocumentType,
  getFileExt,
  deepAssign,
  waitForEvent
};
