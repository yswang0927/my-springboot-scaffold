import { DocumentType } from "./types";

function getFileExt(name) {
  const type = name.split(".").pop() || "";
  return type.toLowerCase();
}

var AppType = ((AppType2) => {
  AppType2[AppType2["word"] = 1] = "word";
  AppType2[AppType2["slide"] = 3] = "slide";
  AppType2[AppType2["cell"] = 2] = "cell";
  AppType2[AppType2["draw"] = 4] = "draw";
  AppType2[AppType2["pdf"] = 5] = "pdf";
  return AppType2;
})(AppType || {});

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
  return type || DocumentType.Word;
}


const ONLYOFFICE_ROOT = (window.APP_BASE_URL || "") + "/assets/onlyoffice";
const ONLYOFFICE_PRELOAD_HTML = "/web-apps/apps/api/documents/preload.html";
const ONLYOFFICE_API_JS = "/web-apps/apps/api/documents/api.js";

export {
  ONLYOFFICE_ROOT,
  ONLYOFFICE_PRELOAD_HTML,
  ONLYOFFICE_API_JS,
  AppType,
  docTypeMap,
  getDocumentType,
  getFileExt
};
