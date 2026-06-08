const pluginsBase = "https://office-plugins.ziziyi.com/v9/sdkjs-plugins";
const allPlugins = [
  "ai",
  "apertium",
  "autocomplete",
  "bergamot",
  "chess",
  "cvbuilder",
  "datepicker",
  "deepl",
  "doc2md",
  "drawio",
  "easybib",
  "glavred",
  "grammalecte",
  "highlightcode",
  "html",
  "icons",
  "idphoto",
  "insertQR",
  "jitsi",
  "languagetool",
  "marketplace",
  "mathpix",
  "mendeley",
  "news",
  "ocr",
  "onlydraw",
  "photoeditor",
  "pixabay",
  "pomodoro",
  "rainbow",
  "speech",
  "speechrecognition",
  "telegram",
  "termef",
  "textcleaner",
  "texthighlighter",
  "thesaurus",
  "translator",
  "typograf",
  "videoembedder",
  "wordpress",
  "wordscounter",
  "youtube",
  "zhipu",
  "zoom",
  "zotero"
];
const featuredPlugins = [
  "marketplace",
  "ai",
  "youtube",
  "jitsi",
  "photoeditor",
  "typograf",
  "languagetool",
  "thesaurus",
  "deepl",
  "zhipu"
];
function getPluginConfigUrl(name) {
  return `${pluginsBase}/${name}/config.json`;
}
function getPluginsData(list) {
  return {
    url: "",
    pluginsData: list.map(getPluginConfigUrl),
    autostart: []
  };
}
export {
  allPlugins,
  featuredPlugins,
  getPluginConfigUrl,
  getPluginsData,
  pluginsBase
};
