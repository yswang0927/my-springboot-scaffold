## 基于 OnlyOffice v9.4.1 版本客户端

1. 修改了很多主要的 `.html` 页面, 在 `<head>` 中增加 `<base href=""/assets/onlyoffice/...>` 标签用于准确解决页面中的相对请求.
   如果需要调整基础路径, 则自行修改.

```shell
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/api/documents/">|' ./web-apps/apps/api/documents/cache-scripts.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/api/documents/">|' ./web-apps/apps/api/documents/preload.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/common/">|' ./web-apps/apps/common/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/common/main/resources/help/">|' ./web-apps/apps/common/main/resources/help/download.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/documenteditor/embed/">|' ./web-apps/apps/documenteditor/embed/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/documenteditor/embed/">|' ./web-apps/apps/documenteditor/embed/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/documenteditor/forms/">|' ./web-apps/apps/documenteditor/forms/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/documenteditor/main/">|' ./web-apps/apps/documenteditor/main/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/documenteditor/main/">|' ./web-apps/apps/documenteditor/main/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/documenteditor/main/resources/help/en/search/">|' ./web-apps/apps/documenteditor/main/resources/help/en/search/search.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/documenteditor/mobile/">|' ./web-apps/apps/documenteditor/mobile/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/documenteditor/mobile/">|' ./web-apps/apps/documenteditor/mobile/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/pdfeditor/main/">|' ./web-apps/apps/pdfeditor/main/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/pdfeditor/main/">|' ./web-apps/apps/pdfeditor/main/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/pdfeditor/main/resources/help/en/search/">|' ./web-apps/apps/pdfeditor/main/resources/help/en/search/search.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/presentationeditor/embed/">|' ./web-apps/apps/presentationeditor/embed/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/presentationeditor/embed/">|' ./web-apps/apps/presentationeditor/embed/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/presentationeditor/main/">|' ./web-apps/apps/presentationeditor/main/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/presentationeditor/main/">|' ./web-apps/apps/presentationeditor/main/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/presentationeditor/main/">|' ./web-apps/apps/presentationeditor/main/index.reporter.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/presentationeditor/main/resources/help/en/search/"/>|' ./web-apps/apps/presentationeditor/main/resources/help/en/search/search.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/presentationeditor/mobile/">|' ./web-apps/apps/presentationeditor/mobile/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/presentationeditor/mobile/">|' ./web-apps/apps/presentationeditor/mobile/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/spreadsheeteditor/embed/">|' ./web-apps/apps/spreadsheeteditor/embed/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/spreadsheeteditor/embed/">|' ./web-apps/apps/spreadsheeteditor/embed/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/spreadsheeteditor/main/">|' ./web-apps/apps/spreadsheeteditor/main/index_internal.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/spreadsheeteditor/main/">|' ./web-apps/apps/spreadsheeteditor/main/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/spreadsheeteditor/main/">|' ./web-apps/apps/spreadsheeteditor/main/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/spreadsheeteditor/main/resources/help/en/search/">|' ./web-apps/apps/spreadsheeteditor/main/resources/help/en/search/search.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/spreadsheeteditor/mobile/">|' ./web-apps/apps/spreadsheeteditor/mobile/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/spreadsheeteditor/mobile/">|' ./web-apps/apps/spreadsheeteditor/mobile/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/visioeditor/embed/">|' ./web-apps/apps/visioeditor/embed/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/visioeditor/embed/">|' ./web-apps/apps/visioeditor/embed/index.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/visioeditor/main/">|' ./web-apps/apps/visioeditor/main/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/visioeditor/main/">|' ./web-apps/apps/visioeditor/main/index.html 
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/visioeditor/main/resources/help/en/search/">|' ./web-apps/apps/visioeditor/main/resources/help/en/search/search.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/visioeditor/mobile/">|' ./web-apps/apps/visioeditor/mobile/index_loader.html
sed -i 's|<head>|<head><base href="/assets/onlyoffice/web-apps/apps/visioeditor/mobile/">|' ./web-apps/apps/visioeditor/mobile/index.html

```

1. 修改 `web-apps/apps/api/documents/api.js` 
```js
function extendAppPath(config,  path) {
    /* yswang
    if ( !config.isLocalFile ) {
        const ver = '/9.4.1-5a01972ab4666a524e6c3249f62256e2';
        if ( ver.lastIndexOf('{{') < 0 && path.indexOf(ver) < 0 ) {
            const pos = path.indexOf('/web-apps/app');
            if ( pos > 0 )
                return [path.slice(0, pos), ver, path.slice(pos)].join('');
        }
    }*/
    return path;
}

```