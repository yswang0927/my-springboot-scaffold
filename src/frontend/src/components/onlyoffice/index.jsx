import { useLayoutEffect, useRef, useEffect } from "react";
import {
    ONLYOFFICE_ROOT,
    ONLYOFFICE_API_JS,
    ONLYOFFICE_PRELOAD_HTML,
    getDocumentType
} from "./utils";
import io, { MockSocket } from "./socket";
import { createFetchProxy } from "./fetch";
import { createXHRProxy } from "./xhr";
import { EditorServer } from "./server";

const OnlyOffice = ({
    params,
    theme = "theme-white",
    language = "zh-CN",
    plugins = "featured"
}) => {
    const serverRef = useRef(null);
    const editorRef = useRef(null);
    const isDirty = useRef(false);

    if (!serverRef.current) {
        serverRef.current = new EditorServer({
            getState: () => ({ plugins })
        });
    }

    const server = serverRef.current;

    useEffect(() => {
        const handleBeforeUnload = (e) => {
            if (isDirty.current) {
                e.preventDefault();
                e.returnValue = "";
            }
        };
        window.addEventListener("beforeunload", handleBeforeUnload);
        return () => {
            window.removeEventListener("beforeunload", handleBeforeUnload);
        };
    }, []);

    useLayoutEffect(() => {
        const apiUrl = ONLYOFFICE_API_JS;
        const searchParams = new URLSearchParams(window.location.search);
        const fileId = searchParams.get("fileId");
        const newDoc = searchParams.get("new");
        if (!fileId && newDoc) {
            server.openNew(newDoc);
        }

        const doc = server.getDocument();
        const user = server.getUser();
        const documentType = getDocumentType(doc.fileType);

        console.log("editor: ", doc, user, documentType);

        let editor = null;
        MockSocket.on("connect", server.handleConnect);
        MockSocket.on("disconnect", server.handleDisconnect);

        const onAppReady = () => {
            const iframe = document.querySelector('iframe[name="frameEditor"]');
            const win = iframe?.contentWindow;
            const doc2 = iframe?.contentDocument;
            if (!doc2 || !win) {
                throw new Error("OnlyOffice-iframe not loaded");
            }

            // 使用代理和Mock来拦截 onlyoffice 的请求, 模拟服务端的响应
            const XHR = createXHRProxy(win.XMLHttpRequest);
            const fetchProxy = createFetchProxy(win);
            const _Worker = win.Worker;
            XHR.use((request) => {
                return server.handleRequest(request);
            });
            fetchProxy.use((request) => {
                return server.handleRequest(request);
            });
            Object.assign(win, {
                io,
                XMLHttpRequest: XHR,
                fetch: fetchProxy,
                Worker: function (url, options) {
                    const u = new URL(url, location.origin);
                    return new _Worker(
                        u.href.replace(u.origin, location.origin),
                        options
                    );
                }
            });
            const script = doc2.createElement("script");
            script.src = apiUrl;
            doc2.body.appendChild(script);
        };

        const createEditor = () => {
            editor = new window.DocsAPI.DocEditor("placeholder", {
                document: {
                    documentType: documentType,
                    fileType: doc.fileType,
                    key: doc.key,
                    title: doc.title,
                    url: doc.url,
                    permissions: {
                        edit: doc.fileType != "pdf",
                        chat: false,
                        rename: true,
                        protect: true,
                        review: false,
                        print: true
                    }
                },
                editorConfig: {
                    lang: language,
                    region: language,
                    // canCoAuthoring: true,
                    coEditing: {
                        mode: "fast",
                        change: false
                        // disable user switching to real-time mode
                    },
                    user: {
                        ...user
                    },
                    // callbackUrl: "https://example.com/url-to-callback.ashx",
                    customization: {
                        help: false,
                        about: false,
                        feedback: false,
                        hideRightMenu: true,
                        uiTheme: theme,
                        features: {
                            spellcheck: {
                                change: false
                            }
                        },
                        suggestFeature: false
                        // anonymous: {
                        //   request: false,
                        //   label: "Guest",
                        // },
                        /*logo: {
                            image: location.origin + "/logo-name_black.svg",
                            imageDark: location.origin + "/logo-name_white.svg",
                            url: location.origin
                            // visible: false,
                        }*/
                    }
                },
                events: {
                    onAppReady: async (e) => {
                        console.log("App ready", e, editor);
                        onAppReady();
                    },
                    onDocumentReady: (e) => {
                        console.log("Document ready", e);
                    },
                    onDocumentStateChange: (e) => {
                        console.log("Document state change", e);
                        if (e.data) {
                            isDirty.current = true;
                        }
                    },
                    onRequestOpen: (e) => {
                        console.log("onRequestOpen", e);
                    },
                    onError: (e) => {
                        console.log("Error", e);
                    },
                    onInfo: (e) => {
                        console.log("Info", e);
                    },
                    onWarning: (e) => {
                        console.log("onWarning", e);
                    },
                    onRequestSaveAs: (e) => {
                        console.log("onRequestSaveAs", e);
                    },
                    onSaveDocument: (e) => {
                        console.log("onSaveDocument", e);
                        isDirty.current = false;
                    },
                    onDownloadAs: (e) => {
                        console.log("onDownloadAs", e);
                    },
                    onSave: (e) => {
                        console.log("onSave", e);
                        isDirty.current = false;
                    },
                    writeFile: async (e) => {
                        console.log("writeFile", e);
                        isDirty.current = false;
                    }
                },
                type: "desktop",
                width: "100%",
                height: "100%"
            });

            Object.assign(window, { editor: editor });

            return editor;
        };

        const loadEditor = async () => {
            if (window.DocsAPI && window.DocsAPI.DocEditor) {
                editorRef.current = createEditor();
            }
            let script = document.querySelector(`script[src="${apiUrl}"]`);
            if (!script) {
                script = document.createElement("script");
                script.src = apiUrl;
                document.head.appendChild(script);
            }
            script.onload = () => {
                editorRef.current = createEditor();
            };
            script.onerror = (e) => {
                console.error("Failed to load OnlyOffice DocsAPI script", e);
            };
        };

        loadEditor();

        return () => {
            MockSocket.off("connect", server.handleConnect);
            MockSocket.off("disconnect", server.handleDisconnect);
            console.log(">> OnlyOffice MockSocket off `connect` and `disconnect`.");

            if (editorRef.current) {
                editorRef.current.destroyEditor();
                editorRef.current = null;
                console.log(">> OnlyOffice Editor destroyed.");
            }
        };
    }, []);

    return <div style={{ position: "relative", width: "100%", height: "100%" }}>
        <div style={{ position: "absolute", left: 0, top: 0, right: 0, bottom: 0 }}>
            <div id="placeholder">
                <iframe style={{ width: 0, height: 0, visibility: 'hidden' }} src={ONLYOFFICE_PRELOAD_HTML} />
            </div>
        </div>
    </div>;
}

export default OnlyOffice;
