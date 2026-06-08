class X2tConverter {
  constructor({ scriptUrl }) {
    this.initPromise = null;
    this.scriptUrl = scriptUrl;
    this.init();
  }
  async init() {
    if (this.initPromise) {
      return await this.initPromise;
    }
    this.initPromise = new Promise(async (resolve, reject) => {
      const script = document.createElement("script");
      script.src = new URL(this.scriptUrl, location.href).href;
      await new Promise((res, rej) => {
        script.onload = () => res();
        script.onerror = () => rej();
        document.head.appendChild(script);
      });
      this.x2t = window.Module;
      await new Promise((res) => {
        this.x2t.onRuntimeInitialized = () => {
          res();
        };
      });
      try {
        this.x2t.FS.mkdir("/working");
        this.x2t.FS.mkdir("/working/media");
        this.x2t.FS.mkdir("/working/fonts");
        this.x2t.FS.mkdir("/working/themes");
      } catch (err) {
        console.error(err);
      }
      resolve();
    });
    await this.initPromise;
  }
  async convert(data, inputFormat, outputFormat, options = {
    media: {},
    fonts: {},
    themes: {}
  }) {
    await this.init();
    const fileFrom = "/working/doc." + inputFormat;
    const fileTo = "/working/doc." + outputFormat;
    const params = `<?xml version="1.0" encoding="utf-8"?>
<TaskQueueDataConvert
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns:xsd="http://www.w3.org/2001/XMLSchema"
>
    <m_sFileFrom>${fileFrom}</m_sFileFrom>
    <m_sThemeDir>/working/themes</m_sThemeDir>
    <m_sFileTo>${fileTo}</m_sFileTo>
    <m_bIsNoBase64>false</m_bIsNoBase64>
    <m_sFontDir>/working/fonts/</m_sFontDir>
</TaskQueueDataConvert>`;
    this.x2t.FS.writeFile(fileFrom, new Uint8Array(data));
    this.x2t.FS.writeFile("/working/params.xml", params);
    try {
      this.x2t.ccall("main1", ["number"], ["string"], ["/working/params.xml"]);
    } catch (e) {
      console.error(e);
    }
    let output = null;
    try {
      output = this.x2t.FS.readFile(fileTo);
    } catch (e) {
      console.error("Failed reading converted file", e);
    }
    let medias = [];
    try {
      medias = await this.readMedia();
    } catch (e) {
      console.error("Failed reading media files", e);
    }
    return { output, medias };
  }
  async readMedia() {
    const images = [];
    const files = this.x2t.FS.readdir("/working/media/");
    files.forEach((file) => {
      if (file !== "." && file !== "..") {
        var fileData = this.x2t.FS.readFile("/working/media/" + file, {
          encoding: "binary"
        });
        images.push({
          name: file,
          data: fileData
        });
      }
    });
    return images;
  }
}
const converter = new X2tConverter({
  scriptUrl: "/x2t/x2t.js"
});
export {
  X2tConverter,
  converter
};
