/*
* MIT Licensed
* https://github.com/23/resumable.js
*
* - jack: 增加内置UI和断点续传等
*/
const Resumable = window.Resumable = function (opts) {
    if (!(this instanceof Resumable)) {
        return new Resumable(opts);
    }

    this.version = 1.0;
    // SUPPORTED BY BROWSER?
    // Check if these features are support by the browser:
    // - File object type
    // - Blob object type
    // - FileList object type
    // - slicing files
    this.support = ((typeof (File) !== 'undefined') && (typeof (Blob) !== 'undefined') && (typeof (FileList) !== 'undefined')
        && (!!Blob.prototype.webkitSlice || !!Blob.prototype.mozSlice || !!Blob.prototype.slice || false));

    if (!this.support) {
        console.error('Your browser not supports HTML5 upload features.');
        return false;
    }

    // PROPERTIES
    var $ = this;
    $.files = [];
    $.defaults = {
        url: '',
        uploadType: 'octet', //'multipart',
        uploadMethod: 'POST',
        testUrl: null,
        testMethod: 'GET',
        testChunks: false,
        chunkSize: 5 * 1024 * 1024,
        forceChunkSize: true,
        simultaneousUploads: 3,
        maxChunkRetries: 3,
        chunkRetryInterval: 300,
        maxFiles: undefined,
        pasteUpload: true, // jack: 是否开启支持粘贴剪切板上传文件
        directoryUpload: false, // jack: 支持上传文件夹(即：上传这个文件夹里的所有文件)
        resumableUpload: false, // jack: 是否开启断点续传，它需要服务器端配合支持，开启后，前端会进行文件的MD5计算，对于大文件MD5计算过程会比较慢
        fileParameterName: 'file',
        chunkNumberParameterName: 'chunkNo',
        chunkSizeParameterName: 'chunkSize',
        currentChunkSizeParameterName: 'currentChunkSize',
        totalChunksParameterName: 'totalChunks',
        totalSizeParameterName: 'fileSize',
        typeParameterName: 'fileType',
        fileIdParameterName: 'fileId',
        fileNameParameterName: 'fileName',
        fileMD5ParameterName: 'fileMD5',
        relativePathParameterName: 'relativePath',
        dragOverClass: 'resum-dragover',
        throttleProgressCallbacks: 0.5,
        query: {},
        headers: {},
        preprocess: null,
        preprocessFile: null,
        prioritizeFirstAndLastChunk: false,
        parameterNamespace: '',
        generateFileId: function() {
            return ((new Date()).getTime() / 1000 | 0).toString(16) + "xxxxxxxxxxxxxxxx".replace(/[x]/g, function() {
                return (16 * Math.random() | 0).toString(16);
            }).toLowerCase();
        },
        getTarget: null,
        permanentErrors: [400, 401, 403, 404, 409, 415, 500, 501],
        withCredentials: false,
        xhrTimeout: 0,
        clearInput: true,
        chunkFormat: 'blob',
        setChunkTypeFromFile: false,
        responseBodyHandle: function(msg) {
            return msg;
        },
        maxFilesErrorCallback: function (files, errorCount) {
            console.warn('请不要一次上传超过 ' + $.getOpt('maxFiles') + ' 个文件');
        },
        minFileSize: 1,
        minFileSizeErrorCallback: function (file, errorCount) {
            console.warn(file.fileName || file.name + ' 文件太小，请上传大于 ' + $h.formatSize($.getOpt('minFileSize')) + ' 大小的文件');
        },
        maxFileSize: 5 * 1024 * 1024 * 1024, // 5GB
        maxFileSizeErrorCallback: function (file, errorCount) {
            console.warn(file.fileName || file.name + ' 文件太大，单个文件不能超过 ' + $h.formatSize($.getOpt('maxFileSize')) + ' 大小');
        },
        fileTypes: [],
        fileTypesErrorCallback: function (file, errorCount) {
            console.warn(file.fileName || file.name + ' 文件类型不被允许，只允许这些类型：' + $.getOpt('fileTypes') + '.');
        }
    };
    $.opts = opts || {};
    $.getOpt = function (o) {
        var $opt = this;
        // Get multiple option if passed an array
        if (o instanceof Array) {
            var options = {};
            $h.each(o, function (option) {
                options[option] = $opt.getOpt(option);
            });
            return options;
        }
        // Otherwise, just return a simple option
        if ($opt instanceof ResumableChunk) {
            if (typeof $opt.opts[o] !== 'undefined') {
                return $opt.opts[o];
            } else {
                $opt = $opt.fileObj;
            }
        }
        if ($opt instanceof ResumableFile) {
            if (typeof $opt.opts[o] !== 'undefined') {
                return $opt.opts[o];
            } else {
                $opt = $opt.resumableObj;
            }
        }
        if ($opt instanceof Resumable) {
            if (typeof $opt.opts[o] !== 'undefined') {
                return $opt.opts[o];
            } else {
                return $opt.defaults[o];
            }
        }
    };
    $.indexOf = function (array, obj) {
        if (array.indexOf) {
            return array.indexOf(obj);
        }
        for (var i = 0; i < array.length; i++) {
            if (array[i] === obj) {
                return i;
            }
        }
        return -1;
    };

    // EVENTS
    // catchAll(event, ...)
    // fileSuccess(file), fileProgress(file), fileAdded(file, event), filesAdded(files, filesSkipped), fileRetry(file),
    // fileError(file, message), complete(), progress(), error(message, file), pause()
    $.events = [];
    $.on = function (event, callback) {
        $.events.push(event.toLowerCase(), callback);
    };
    $.fire = function () {
        // `arguments` is an object, not array, in FF, so:
        var args = [];
        for (var i = 0; i < arguments.length; i++) {
            args.push(arguments[i]);
        }
        // Find event listeners, and support pseudo-event `catchAll`
        var event = args[0].toLowerCase();
        for (var i = 0; i <= $.events.length; i += 2) {
            if ($.events[i] == event) {
                $.events[i + 1].apply($, args.slice(1));
            }
            if ($.events[i] == 'catchall') {
                $.events[i + 1].apply(null, args);
            }
        }
        if (event == 'fileerror') {
            $.fire('error', args[2], args[1]);
        }
        if (event == 'fileprogress') {
            $.fire('progress');
        }
    };

    // INTERNAL HELPER METHODS (handy, but ultimately not part of uploading)
    var $h = $.utils = {
        stopEvent: function (e) {
            e.stopPropagation();
            e.preventDefault();
        },
        each: function (o, callback) {
            if (typeof (o.length) !== 'undefined') {
                for (var i = 0; i < o.length; i++) {
                    // Array or FileList
                    if (callback(o[i]) === false) {
                        return;
                    }
                }
            } else {
                for (i in o) {
                    // Object
                    if (callback(i, o[i]) === false) {
                        return;
                    }
                }
            }
        },
        generateFileId: function (file, event) {
            var custom = $.getOpt('generateFileId');
            if (typeof custom === 'function') {
                return custom(file, event);
            }
            var relativePath = file.webkitRelativePath || file.relativePath || file.fileName || file.name; // Some confusion in different versions of Firefox
            var size = file.size;
            return (size + '-' + relativePath.replace(/[^0-9a-zA-Z_-]/img, ''));
        },
        contains: function (array, test) {
            var result = false;
            $h.each(array, function (value) {
                if (value == test) {
                    result = true;
                    return false;
                }
                return true;
            });
            return result;
        },
        formatSize: function (size) {
            if (size < 1024) {
                return size + ' bytes';
            } else if (size < 1024 * 1024) {
                return (size / 1024.0).toFixed(0) + ' KB';
            } else if (size < 1024 * 1024 * 1024) {
                return (size / 1024.0 / 1024.0).toFixed(1) + ' MB';
            } else {
                return (size / 1024.0 / 1024.0 / 1024.0).toFixed(1) + ' GB';
            }
        },
        getTarget: function (request, params) {
            var target = $.getOpt('url');

            if (request === 'test' && $.getOpt('testUrl')) {
                target = $.getOpt('testUrl') === '/' ? $.getOpt('url') : $.getOpt('testUrl');
            }

            if (typeof target === 'function') {
                return target(params);
            }

            var separator = target.indexOf('?') < 0 ? '?' : '&';
            var joinedParams = params.join('&');

            if (joinedParams) {
                target = target + separator + joinedParams;
            }
            return target;
        }
    };

    var onDrop = function (e) {
        e.currentTarget.classList.remove($.getOpt('dragOverClass'));
        $h.stopEvent(e);

        //handle dropped things as items if we can (this lets us deal with folders nicer in some cases)
        if (e.dataTransfer && e.dataTransfer.items) {
            loadFiles(e.dataTransfer.items, e);
        }
        //else handle them as files
        else if (e.dataTransfer && e.dataTransfer.files) {
            loadFiles(e.dataTransfer.files, e);
        }
    };
    var onDragLeave = function (e) {
        e.currentTarget.classList.remove($.getOpt('dragOverClass'));
    };
    var onDragOverEnter = function (e) {
        e.preventDefault();
        var dt = e.dataTransfer;
        if ($.indexOf(dt.types, "Files") >= 0) { // only for file drop
            e.stopPropagation();
            dt.dropEffect = "copy";
            dt.effectAllowed = "copy";
            e.currentTarget.classList.add($.getOpt('dragOverClass'));
        } else { // not work on IE/Edge....
            dt.dropEffect = "none";
            dt.effectAllowed = "none";
        }
    };

    /**
     * processes a single upload item (file or directory)
     * @param {Object} item item to upload, may be file or directory entry
     * @param {string} path current file path
     * @param {File[]} items list of files to append new items to
     * @param {Function} cb callback invoked when item is processed
     */
    function processItem(item, path, items, cb) {
        var entry;
        if (item.isFile) {
            // file provided
            return item.file(function (file) {
                file.relativePath = path + file.name;
                items.push(file);
                cb();
            });
        } else if (item.isDirectory) {
            // item is already a directory entry, just assign
            entry = item;
        } else if (item instanceof File) {
            items.push(item);
        }
        if ('function' === typeof item.webkitGetAsEntry) {
            // get entry from file object
            entry = item.webkitGetAsEntry();
        }
        if (entry && entry.isDirectory) {
            // directory provided, process it
            return processDirectory(entry, path + entry.name + '/', items, cb);
        }
        if ('function' === typeof item.getAsFile) {
            // item represents a File object, convert it
            item = item.getAsFile();
            if (item instanceof File) {
                item.relativePath = path + item.name;
                items.push(item);
            }
        }
        cb(); // indicate processing is done
    }

    /**
     * cps-style list iteration.
     * invokes all functions in list and waits for their callback to be
     * triggered.
     * @param  {Function[]}   items list of functions expecting callback parameter
     * @param  {Function} cb    callback to trigger after the last callback has been invoked
     */
    function processCallbacks(items, cb) {
        if (!items || items.length === 0) {
            // empty or no list, invoke callback
            return cb();
        }
        // invoke current function, pass the next part as continuation
        items[0](function () {
            processCallbacks(items.slice(1), cb);
        });
    }

    /**
     * recursively traverse directory and collect files to upload
     * @param  {Object}   directory directory to process
     * @param  {string}   path      current path
     * @param  {File[]}   items     target list of items
     * @param  {Function} cb        callback invoked after traversing directory
     */
    function processDirectory(directory, path, items, cb) {
        var dirReader = directory.createReader();
        var allEntries = [];
        function readEntries() {
            dirReader.readEntries(function (entries) {
                if (entries.length) {
                    allEntries = allEntries.concat(entries);
                    return readEntries();
                }
                // process all conversion callbacks, finally invoke own one
                processCallbacks(
                    allEntries.map(function (entry) {
                        // bind all properties except for callback
                        return processItem.bind(null, entry, path, items);
                    }),
                    cb
                );
            });
        }
        readEntries();
    }

    /**
     * process items to extract files to be uploaded
     * @param  {File[]} items items to process
     * @param  {Event} event event that led to upload
     */
    function loadFiles(items, event) {
        if (!items.length) {
            return; // nothing to do
        }
        $.fire('beforeAdd');
        var files = [];
        processCallbacks(
            Array.prototype.map.call(items, function (item) {
                // bind all properties except for callback
                var entry = item;
                if ('function' === typeof item.webkitGetAsEntry) {
                    entry = item.webkitGetAsEntry();
                }
                return processItem.bind(null, entry, "", files);
            }),
            function () {
                if (files.length) {
                    // at least one file found
                    appendFilesFromFileList(files, event);
                }
            }
        );
    };

    var appendFilesFromFileList = function (fileList, event) {
        var o = $.getOpt(['maxFiles', 'minFileSize', 'maxFileSize', 'maxFilesErrorCallback', 'minFileSizeErrorCallback',
            'maxFileSizeErrorCallback', 'fileTypes', 'fileTypesErrorCallback', 'resumableUpload']);

        // check for uploading too many files
        var errorCount = 0;
        if (typeof (o.maxFiles) !== 'undefined' && o.maxFiles < (fileList.length + $.files.length)) {
            // if single-file upload, file is already added, and trying to add 1 new file, simply replace the already-added file
            if (o.maxFiles === 1 && $.files.length === 1 && fileList.length === 1) {
                $.removeFile($.files[0]);
            } else {
                o.maxFilesErrorCallback(fileList, errorCount++);
                return false;
            }
        }

        var files = [], filesSkipped = [], remaining = fileList.length;
        var decreaseReamining = function () {
            if (!--remaining) {
                // all files processed, trigger event
                if (!files.length && !filesSkipped.length) {
                    // no succeeded files, just skip
                    return;
                }
                window.setTimeout(function () {
                    $.fire('filesAdded', files, filesSkipped);
                }, 0);

                // jack: calc file-md5 if need(resumableUpload=true)
                $.canResumableUpload && (files.forEach(function(rf) {
                   $._calcFileMD5(rf);
                }));
            }
        };
        $h.each(fileList, function (file) {
            var fileName = file.newName || file.fileName || file.name; // jack: file.newName是来自剪切板中的临时文件名
            var fileType = file.type; // e.g image/png, video/mp4

            if (o.fileTypes.length > 0) {
                var fileTypeFound = false;
                // jack add
                var fileExtMatched = false, fileTypeMatched = false;
                for (var index in o.fileTypes) {
                    // For good behaviour we do some inital sanitizing. Remove spaces and lowercase all
                    o.fileTypes[index] = o.fileTypes[index].replace(/\s/g, '').toLowerCase();
                    // Allowing for both [extension, .extension, mime/type, mime/*]
                    var extension = ((o.fileTypes[index].match(/^[^.][^/]+$/)) ? '.' : '') + o.fileTypes[index];

                    fileExtMatched = (fileName.substring(fileName.length - extension.length).toLowerCase() === extension);
                    //If MIME type, check for wildcard or if extension matches the files tiletype
                    fileTypeMatched = (extension.indexOf('/') !== -1 && (
                        (extension.indexOf('*') !== -1 && fileType.substring(0, extension.indexOf('*')) === extension.substring(0, extension.indexOf('*')))
                        || (fileType === extension)
                    ));

                    if (fileExtMatched || fileTypeMatched) {
                        fileTypeFound = true;
                        break;
                    }
                }

                // jack: 增加严格检查：通过文件扩展名从MIME_TYPES中获取mime-type和fileType进行二次判断
                // ps: 似乎没用，因为浏览器是根据文件的扩展名来设置fileType的，而不是内容的MimeType类型；
                // 因此，即使将.txt的文件重命名为.png，浏览器给出的file.type是image/png而不是text/plain。
                if (fileTypeFound && fileExtMatched && !fileTypeMatched) {
                    var dotIndex = fileName.lastIndexOf('.');
                    if (dotIndex !== -1) {
                        var fileExt = fileName.substring(dotIndex + 1).replace(/\s/g, '').toLowerCase();
                        var theMimeType = Resumable.MIME_TYPES[fileExt];
                        // 注册的扩展名MimeType和实际解析出来的文件类型不匹配，认为是篡改文件扩展名
                        if (theMimeType && theMimeType !== fileType) {
                            fileTypeFound = false;
                        }
                    }
                }

                if (!fileTypeFound) {
                    o.fileTypesErrorCallback(file, errorCount++);
                    return true;
                }
            }

            if (typeof (o.minFileSize) !== 'undefined' && file.size < o.minFileSize) {
                o.minFileSizeErrorCallback(file, errorCount++);
                return true;
            }
            if (typeof (o.maxFileSize) !== 'undefined' && file.size > o.maxFileSize) {
                o.maxFileSizeErrorCallback(file, errorCount++);
                return true;
            }

            function addFile(fileId) {
                if (!$.getFromFileId(fileId)) {
                    (function () {
                        // jack add 检查是否存在相同的文件
                        var fileExists = false;
                        $.files.forEach(function(f) {
                            if (f.fileName === fileName && f.size === file.size) {
                                fileExists = true;
                            }
                        });
                        if (fileExists) {
                            filesSkipped.push(file);
                            return;
                        }

                        file.fileId = fileId;
                        var f = new ResumableFile($, file, fileId);
                        $.files.push(f);
                        files.push(f);
                        f.container = (typeof event != 'undefined' ? event.srcElement : null);
                        window.setTimeout(function () {
                            $.fire('fileAdded', f, event);
                        }, 0);
                    })();
                } else {
                    filesSkipped.push(file);
                }
                decreaseReamining();
            }

            // directories have size == 0
            var uniqueIdentifier = $h.generateFileId(file, event);
            if (uniqueIdentifier && typeof uniqueIdentifier.then === 'function') {
                // Promise or Promise-like object provided as unique identifier
                uniqueIdentifier.then(
                    function (uid) {
                        // unique identifier generation succeeded
                        addFile(uid);
                    },
                    function () {
                        // unique identifier generation failed
                        // skip further processing, only decrease file count
                        decreaseReamining();
                    }
                );
            } else {
                // non-Promise provided as unique identifier, process synchronously
                addFile(uniqueIdentifier);
            }
        });
    };

    // INTERNAL OBJECT TYPES
    function ResumableFile(resumableObj, file, fileId) {
        var $ = this;
        $.opts = {};
        $.getOpt = resumableObj.getOpt;
        $.resumableObj = resumableObj;
        $.file = file;
        $.fileId = fileId;
        $.fileName = file.newName || file.fileName || file.name; // jack:file.newName是来自剪切板中的临时文件名
        $.size = file.size;
        $.readableSize = resumableObj.utils.formatSize(file.size);  // jack
        $.lastModified = file.lastModified || 0; // jack
        $.md5 = ''; // jack: file-md5 value
        $.relativePath = file.relativePath || file.webkitRelativePath || $.fileName;
        $._prevProgress = 0;
        $._pause = false;
        $.container = '';
        $.preprocessState = 0; // 0 = unprocessed, 1 = processing, 2 = finished
        var _error = fileId !== undefined;

        // Callback when something happens within the chunk
        var chunkEvent = function (event, message) {
            // event can be 'progress', 'success', 'error' or 'retry'
            switch (event) {
                case 'progress':
                    $.resumableObj.fire('fileProgress', $, message);
                    break;
                case 'error':
                    $.abort();
                    _error = true;
                    $.chunks = [];
                    $.resumableObj.fire('fileError', $, message);
                    break;
                case 'success':
                    if (_error) return;
                    $.resumableObj.fire('fileProgress', $, message); // it's at least progress
                    if ($.isComplete()) {
                        $.resumableObj.fire('fileSuccess', $, message);
                    }
                    break;
                case 'retry':
                    $.resumableObj.fire('fileRetry', $);
                    break;
            }
        };

        // jack
        $.setMD5 = function(md5) {
            $.md5 = md5;
            console.log('>>文件<'+ $.fileName +'> MD5 = '+ md5);
        };
        $.isReady = function() {
            return (($.getOpt('resumableUpload') === false) || $.md5 !== '');
        };

        // Main code to set up a file object with chunks,
        // packaged to be able to handle retries if needed.
        $.chunks = [];
        $.abort = function () {
            // Stop current uploads
            var abortCount = 0;
            $h.each($.chunks, function (c) {
                if (c.status() == 'uploading') {
                    c.abort();
                    abortCount++;
                }
            });
            if (abortCount > 0) {
                $.resumableObj.fire('fileProgress', $);
            }
        };
        $.cancel = function () {
            // Reset this file to be void
            var _chunks = $.chunks;
            $.chunks = [];
            // Stop current uploads
            $h.each(_chunks, function (c) {
                if (c.status() == 'uploading') {
                    c.abort();
                    $.resumableObj.uploadNextChunk();
                }
            });
            $.resumableObj.removeFile($);
            $.resumableObj.fire('fileProgress', $);
        };
        $.retry = function () {
            $.bootstrap();
            var firedRetry = false;
            $.resumableObj.on('chunkingComplete', function () {
                if (!firedRetry) {
                    $.resumableObj.upload();
                }
                firedRetry = true;
            });
        };
        $.bootstrap = function () {
            $.abort();
            _error = false;
            // Rebuild stack of chunks from file
            $.chunks = [];
            $._prevProgress = 0;
            var round = $.getOpt('forceChunkSize') ? Math.ceil : Math.floor;
            var maxOffset = Math.max(round($.file.size / $.getOpt('chunkSize')), 1);
            for (var offset = 0; offset < maxOffset; offset++) {
                (function (offset) {
                    $.chunks.push(new ResumableChunk($.resumableObj, $, offset, chunkEvent));
                    $.resumableObj.fire('chunkingProgress', $, offset / maxOffset);
                })(offset);
            }
            window.setTimeout(function () {
                $.resumableObj.fire('chunkingComplete', $);
            }, 0);
        };
        $.progress = function () {
            if (_error) {
                return 1;
            }
            // Sum up progress across everything
            var ret = 0;
            var error = false;
            $h.each($.chunks, function (c) {
                if (c.status() == 'error') {
                    error = true;
                }
                ret += c.progress(true); // get chunk progress relative to entire file
            });
            ret = (error ? 1 : (ret > 0.99999 ? 1 : ret));
            ret = Math.max($._prevProgress, ret); // We don't want to lose percentages when an upload is paused
            $._prevProgress = ret;
            return ret;
        };
        $.isUploading = function () {
            var uploading = false;
            $h.each($.chunks, function (chunk) {
                if (chunk.status() == 'uploading') {
                    uploading = true;
                    return false;
                }
            });
            return uploading;
        };
        $.isComplete = function () {
            var outstanding = false;
            if ($.preprocessState === 1) {
                return false;
            }
            $h.each($.chunks, function (chunk) {
                var status = chunk.status();
                if (status == 'pending' || status == 'uploading' || chunk.preprocessState === 1) {
                    outstanding = true;
                    return false;
                }
            });
            return (!outstanding);
        };
        $.pause = function (pause) {
            if (typeof (pause) === 'undefined') {
                $._pause = ($._pause ? false : true);
            } else {
                $._pause = pause;
            }
        };
        $.isPaused = function () {
            return $._pause;
        };
        $.preprocessFinished = function () {
            $.preprocessState = 2;
            $.upload();
        };
        $.upload = function () {
            var found = false;
            // jack: add $.isReady()
            if ($.isPaused() === false && $.isReady()) {
                var preprocess = $.getOpt('preprocessFile');
                if (typeof preprocess === 'function') {
                    switch ($.preprocessState) {
                        case 0:
                            $.preprocessState = 1;
                            preprocess($);
                            return true;
                        case 1:
                            return true;
                        case 2:
                            break;
                    }
                }
                $h.each($.chunks, function (chunk) {
                    if (chunk.status() == 'pending' && chunk.preprocessState !== 1) {
                        chunk.send();
                        found = true;
                        return false;
                    }
                });
            }
            return (found);
        };
        $.markChunksCompleted = function (chunkNumber) {
            if (!$.chunks || $.chunks.length <= chunkNumber) {
                return;
            }
            for (var num = 0; num < chunkNumber; num++) {
                $.chunks[num].markComplete = true;
            }
        };

        // Bootstrap and return
        $.resumableObj.fire('chunkingStart', $);
        $.bootstrap();
        return (this);
    }

    function ResumableChunk(resumableObj, fileObj, offset, callback) {
        var $ = this;
        $.opts = {};
        $.getOpt = resumableObj.getOpt;
        $.resumableObj = resumableObj;
        $.fileObj = fileObj;
        $.fileObjSize = fileObj.size;
        $.fileObjType = fileObj.file.type;
        $.offset = offset;
        $.callback = callback;
        $.lastProgressCallback = (new Date());
        $.tested = false;
        $.retries = 0;
        $.pendingRetry = false;
        $.preprocessState = 0; // 0 = unprocessed, 1 = processing, 2 = finished
        $.markComplete = false;

        // Computed properties
        var chunkSize = $.getOpt('chunkSize');
        $.loaded = 0;
        $.startByte = $.offset * chunkSize;
        $.endByte = Math.min($.fileObjSize, ($.offset + 1) * chunkSize);
        if ($.fileObjSize - $.endByte < chunkSize && !$.getOpt('forceChunkSize')) {
            // The last chunk will be bigger than the chunk size, but less than 2*chunkSize
            $.endByte = $.fileObjSize;
        }
        $.xhr = null;

        // test() makes a GET request without any data to see if the chunk has already been uploaded in a previous session
        $.test = function () {
            // Set up request and listen for event
            $.xhr = new XMLHttpRequest();
            var testHandler = function (e) {
                $.tested = true;
                var status = $.status();
                if (status == 'success') {
                    $.callback(status, $.message());
                    $.resumableObj.uploadNextChunk();
                } else {
                    $.send();
                }
            };
            $.xhr.addEventListener('load', testHandler, false);
            $.xhr.addEventListener('error', testHandler, false);
            $.xhr.addEventListener('timeout', testHandler, false);

            // Add data from the query options
            var params = [];
            var parameterNamespace = $.getOpt('parameterNamespace');
            var customQuery = $.getOpt('query');
            if (typeof customQuery == 'function') {
                customQuery = customQuery($.fileObj, $);
            }
            $h.each(customQuery, function (k, v) {
                params.push([encodeURIComponent(parameterNamespace + k), encodeURIComponent(v)].join('='));
            });
            // jack add
            params.push(['resumableUpload', $.getOpt('resumableUpload')].join('='));
            // Add extra data to identify chunk
            params = params.concat(
                [
                    // define key/value pairs for additional parameters
                    ['chunkNumberParameterName', $.offset + 1],
                    ['chunkSizeParameterName', $.getOpt('chunkSize')],
                    ['currentChunkSizeParameterName', $.endByte - $.startByte],
                    ['totalSizeParameterName', $.fileObjSize],
                    ['typeParameterName', $.fileObjType],
                    ['fileIdParameterName', $.fileObj.fileId],
                    ['fileNameParameterName', $.fileObj.fileName],
                    ['fileMD5ParameterName', $.fileObj.md5],
                    ['relativePathParameterName', $.fileObj.relativePath],
                    ['totalChunksParameterName', $.fileObj.chunks.length]
                ].filter(function (pair) {
                    // include items that resolve to truthy values
                    // i.e. exclude false, null, undefined and empty strings
                    return $.getOpt(pair[0]);
                }).map(function (pair) {
                    // map each key/value pair to its final form
                    return [
                        parameterNamespace + $.getOpt(pair[0]),
                        encodeURIComponent(pair[1])
                    ].join('=');
                })
            );
            // Append the relevant chunk and send it
            $.xhr.open($.getOpt('testMethod'), $h.getTarget('test', params));
            $.xhr.timeout = $.getOpt('xhrTimeout');
            $.xhr.withCredentials = $.getOpt('withCredentials');
            // Add data from header options
            var customHeaders = $.getOpt('headers');
            if (typeof customHeaders === 'function') {
                customHeaders = customHeaders($.fileObj, $);
            }
            $h.each(customHeaders, function (k, v) {
                $.xhr.setRequestHeader(k, v);
            });
            $.xhr.send(null);
        };

        $.preprocessFinished = function () {
            $.preprocessState = 2;
            $.send();
        };

        // send() uploads the actual data in a POST call
        $.send = function () {
            var preprocess = $.getOpt('preprocess');
            if (typeof preprocess === 'function') {
                switch ($.preprocessState) {
                    case 0:
                        $.preprocessState = 1;
                        preprocess($);
                        return;
                    case 1:
                        return;
                    case 2:
                        break;
                }
            }
            if ($.getOpt('testChunks') && !$.tested) {
                $.test();
                return;
            }

            // Set up request and listen for event
            $.xhr = new XMLHttpRequest();

            // Progress
            $.xhr.upload.addEventListener('progress', function (e) {
                if ((new Date()) - $.lastProgressCallback > $.getOpt('throttleProgressCallbacks') * 1000) {
                    $.callback('progress');
                    $.lastProgressCallback = (new Date());
                }
                $.loaded = e.loaded || 0;
            }, false);
            $.loaded = 0;
            $.pendingRetry = false;
            $.callback('progress');

            // Done (either done, failed or retry)
            var doneHandler = function (e) {
                var status = $.status();
                if (status == 'success' || status == 'error') {
                    $.callback(status, $.message());
                    $.resumableObj.uploadNextChunk();
                } else {
                    $.callback('retry', $.message());
                    $.abort();
                    $.retries++;
                    var retryInterval = $.getOpt('chunkRetryInterval');
                    if (retryInterval !== undefined) {
                        $.pendingRetry = true;
                        setTimeout($.send, retryInterval);
                    } else {
                        $.send();
                    }
                }
            };
            $.xhr.addEventListener('load', doneHandler, false);
            $.xhr.addEventListener('error', doneHandler, false);
            $.xhr.addEventListener('timeout', doneHandler, false);

            // Set up the basic query data from Resumable
            var query = [
                ['chunkNumberParameterName', $.offset + 1],
                ['chunkSizeParameterName', $.getOpt('chunkSize')],
                ['currentChunkSizeParameterName', $.endByte - $.startByte],
                ['totalSizeParameterName', $.fileObjSize],
                ['typeParameterName', $.fileObjType],
                ['fileIdParameterName', $.fileObj.fileId],
                ['fileNameParameterName', $.fileObj.fileName],
                ['fileMD5ParameterName', $.fileObj.md5],
                ['relativePathParameterName', $.fileObj.relativePath],
                ['totalChunksParameterName', $.fileObj.chunks.length]
            ].filter(function (pair) {
                // include items that resolve to truthy values
                // i.e. exclude false, null, undefined and empty strings
                return $.getOpt(pair[0]);
            }).reduce(function (query, pair) {
                // assign query key/value
                query[$.getOpt(pair[0])] = pair[1];
                return query;
            }, {});
            // jack add
            query['resumableUpload'] = $.getOpt('resumableUpload');
            // Mix in custom data
            var customQuery = $.getOpt('query');
            if (typeof customQuery == 'function') {
                customQuery = customQuery($.fileObj, $);
            }
            $h.each(customQuery, function (k, v) {
                query[k] = v;
            });

            //var func = ($.fileObj.file.slice ? 'slice' : ($.fileObj.file.mozSlice ? 'mozSlice' : ($.fileObj.file.webkitSlice ? 'webkitSlice' : 'slice')));
            //var bytes = $.fileObj.file[func]($.startByte, $.endByte, $.getOpt('setChunkTypeFromFile') ? $.fileObj.file.type : "");
            var fileSlice = File.prototype.slice || File.prototype.webkitSlice || File.prototype.mozSlice;
            var bytes = fileSlice.call($.fileObj.file, $.startByte, $.endByte, $.getOpt('setChunkTypeFromFile') ? $.fileObj.file.type : "");
            var data = null;
            var params = [];

            var parameterNamespace = $.getOpt('parameterNamespace');
            if ($.getOpt('uploadType') === 'octet') {
                // Add data from the query options
                data = bytes;
                $h.each(query, function (k, v) {
                    params.push([encodeURIComponent(parameterNamespace + k), encodeURIComponent(v)].join('='));
                });
            } else {
                // Add data from the query options
                data = new FormData();
                $h.each(query, function (k, v) {
                    data.append(parameterNamespace + k, v);
                    params.push([encodeURIComponent(parameterNamespace + k), encodeURIComponent(v)].join('='));
                });
                if ($.getOpt('chunkFormat') == 'blob') {
                    data.append(parameterNamespace + $.getOpt('fileParameterName'), bytes, $.fileObj.fileName);
                } else if ($.getOpt('chunkFormat') == 'base64') {
                    var fr = new FileReader();
                    fr.onload = function (e) {
                        data.append(parameterNamespace + $.getOpt('fileParameterName'), fr.result);
                        $.xhr.send(data);
                    };
                    fr.readAsDataURL(bytes);
                }
            }

            var target = $h.getTarget('upload', params);
            var method = $.getOpt('uploadMethod');

            $.xhr.open(method, target);
            if ($.getOpt('uploadType') === 'octet') {
                $.xhr.setRequestHeader('Content-Type', 'application/octet-stream');
            }
            $.xhr.timeout = $.getOpt('xhrTimeout');
            $.xhr.withCredentials = $.getOpt('withCredentials');
            // Add data from header options
            var customHeaders = $.getOpt('headers');
            if (typeof customHeaders === 'function') {
                customHeaders = customHeaders($.fileObj, $);
            }

            $h.each(customHeaders, function (k, v) {
                $.xhr.setRequestHeader(k, v);
            });

            if ($.getOpt('chunkFormat') == 'blob') {
                $.xhr.send(data);
            }
        };
        $.abort = function () {
            // Abort and reset
            if ($.xhr) {
                $.xhr.abort();
            }
            $.xhr = null;
        };
        $.status = function () {
            // Returns: 'pending', 'uploading', 'success', 'error'
            if ($.pendingRetry) {
                // if pending retry then that's effectively the same as actively uploading,
                // there might just be a slight delay before the retry starts
                return 'uploading';
            } else if ($.markComplete) {
                return 'success';
            } else if (!$.xhr) {
                return 'pending';
            } else if ($.xhr.readyState < 4) {
                // Status is really 'OPENED', 'HEADERS_RECEIVED' or 'LOADING' - meaning that stuff is happening
                return 'uploading';
            } else {
                if ($.xhr.status == 200 || $.xhr.status == 201) {
                    // HTTP 200, 201 (created)
                    return 'success';
                } else if ($h.contains($.getOpt('permanentErrors'), $.xhr.status) || $.retries >= $.getOpt('maxChunkRetries')) {
                    // HTTP 400, 404, 409, 415, 500, 501 (permanent error)
                    return 'error';
                } else {
                    // this should never happen, but we'll reset and queue a retry
                    // a likely case for this would be 503 service unavailable
                    $.abort();
                    return 'pending';
                }
            }
        };
        $.message = function () {
            var msg = ($.xhr ? $.xhr.responseText : '');
            var rspBodyHandle = $.getOpt('responseBodyHandle');
            if (typeof rspBodyHandle === 'function') {
                return rspBodyHandle.call($, msg);
            }
            return msg;
        };
        $.progress = function (relative) {
            if (typeof (relative) === 'undefined') {
                relative = false;
            }
            var factor = (relative ? ($.endByte - $.startByte) / $.fileObjSize : 1);
            if ($.pendingRetry) {
                return 0;
            }
            if ((!$.xhr || !$.xhr.status) && !$.markComplete) {
                factor *= .95;
            }
            var s = $.status();
            switch (s) {
                case 'success':
                case 'error':
                    return (1 * factor);
                case 'pending':
                    return (0 * factor);
                default:
                    return ($.loaded / ($.endByte - $.startByte) * factor);
            }
        };
        return this;
    }

    // QUEUE
    $.uploadNextChunk = function () {
        var found = false;
        // In some cases (such as videos) it's really handy to upload the first
        // and last chunk of a file quickly; this let's the server check the file's
        // metadata and determine if there's even a point in continuing.
        if ($.getOpt('prioritizeFirstAndLastChunk')) {
            $h.each($.files, function (file) {
                if (file.chunks.length && file.chunks[0].status() == 'pending' && file.chunks[0].preprocessState === 0) {
                    file.chunks[0].send();
                    found = true;
                    return false;
                }
                if (file.chunks.length > 1 && file.chunks[file.chunks.length - 1].status() == 'pending' && file.chunks[file.chunks.length - 1].preprocessState === 0) {
                    file.chunks[file.chunks.length - 1].send();
                    found = true;
                    return false;
                }
            });
            if (found) {
                return true;
            }
        }

        // Now, simply look for the next, best thing to upload
        $h.each($.files, function (file) {
            found = file.upload();
            if (found) {
                return false;
            }
        });
        if (found) {
            return true;
        }

        // The are no more outstanding chunks to upload, check is everything is done
        var outstanding = false;
        $h.each($.files, function (file) {
            if (!file.isComplete()) {
                outstanding = true;
                return false;
            }
        });
        if (!outstanding) {
            // All chunks have been uploaded, complete
            $.fire('complete');
        }
        return false;
    };

    // PUBLIC METHODS FOR RESUMABLE.JS
    $.assignBrowse = function (domNodes, isDirectory) {
        if (typeof (domNodes.length) == 'undefined') {
            domNodes = [domNodes];
        }
        $h.each(domNodes, function (domNode) {
            var input;
            if (domNode.tagName === 'INPUT' && domNode.type === 'file') {
                input = domNode;
            } else {
                input = document.createElement('input');
                input.setAttribute('type', 'file');
                input.style.cssText = 'display:none;opacity:0;width:20px;';
                domNode.addEventListener('click', function () {
                    input.style.display = 'block';
                    input.focus();
                    input.click();
                    input.style.display = 'none';
                }, false);
                domNode.appendChild(input);
            }
            var maxFiles = $.getOpt('maxFiles');
            if (typeof (maxFiles) === 'undefined' || maxFiles != 1) {
                input.setAttribute('multiple', 'multiple');
            } else {
                input.removeAttribute('multiple');
            }
            if (isDirectory) {
                input.setAttribute('webkitdirectory', 'webkitdirectory');
                input.setAttribute('mozdirectory', 'mozdirectory');
            } else {
                input.removeAttribute('webkitdirectory');
                input.removeAttribute('mozdirectory');
            }
            var fileTypes = $.getOpt('fileTypes');
            if (typeof (fileTypes) !== 'undefined' && fileTypes.length >= 1) {
                input.setAttribute('accept', fileTypes.map(function (e) {
                    e = e.replace(/\s/g, '').toLowerCase();
                    if (e.match(/^[^.][^/]+$/)) {
                        e = '.' + e;
                    }
                    return e;
                }).join(','));
            } else {
                input.setAttribute('accept', '*');
            }
            // When new files are added, simply append them to the overall list
            input.addEventListener('change', function (e) {
                appendFilesFromFileList(e.target.files, e);
                var clearInput = $.getOpt('clearInput');
                if (clearInput) {
                    e.target.value = '';
                }
            }, false);
        });
    };
    $.assignDrop = function (domNodes) {
        if (typeof (domNodes.length) == 'undefined') {
            domNodes = [domNodes];
        }

        $h.each(domNodes, function (domNode) {
            domNode.addEventListener('dragover', onDragOverEnter, false);
            domNode.addEventListener('dragenter', onDragOverEnter, false);
            domNode.addEventListener('dragleave', onDragLeave, false);
            domNode.addEventListener('drop', onDrop, false);
        });
    };
    $.unAssignDrop = function (domNodes) {
        if (typeof (domNodes.length) == 'undefined') {
            domNodes = [domNodes];
        }

        $h.each(domNodes, function (domNode) {
            domNode.removeEventListener('dragover', onDragOverEnter);
            domNode.removeEventListener('dragenter', onDragOverEnter);
            domNode.removeEventListener('dragleave', onDragLeave);
            domNode.removeEventListener('drop', onDrop);
        });
    };
    $.isUploading = function () {
        var uploading = false;
        $h.each($.files, function (file) {
            if (file.isUploading()) {
                uploading = true;
                return false;
            }
        });
        return uploading;
    };
    $.upload = function () {
        // Make sure we don't start too many uploads at once
        if ($.isUploading()) {
            return;
        }
        // Kick off the queue
        $.fire('uploadStart');
        for (var num = 1; num <= $.getOpt('simultaneousUploads'); num++) {
            $.uploadNextChunk();
        }
    };
    $.pause = function () {
        // Resume all chunks currently being uploaded
        $h.each($.files, function (file) {
            file.abort();
        });
        $.fire('pause');
    };
    $.cancel = function () {
        $.fire('beforeCancel');
        for (var i = $.files.length - 1; i >= 0; i--) {
            $.files[i].cancel();
        }
        $.fire('cancel');
    };
    $.progress = function () {
        var totalDone = 0;
        var totalSize = 0;
        // Resume all chunks currently being uploaded
        $h.each($.files, function (file) {
            totalDone += file.progress() * file.size;
            totalSize += file.size;
        });
        return (totalSize > 0 ? totalDone / totalSize : 0);
    };
    $.addFile = function (file, event) {
        appendFilesFromFileList([file], event);
    };
    $.addFiles = function (files, event) {
        appendFilesFromFileList(files, event);
    };
    $.getFiles = function() {
        return $.files;
    };
    $.removeFile = function (file) {
        for (var i = $.files.length - 1; i >= 0; i--) {
            if ($.files[i] === file) {
                $.files.splice(i, 1);
                $.fire('fileRemoved', file);
            }
        }
    };
    $.removeFileById = function (fileId) {
        for (var i = $.files.length - 1; i >= 0; i--) {
            if ($.files[i].fileId === fileId) {
                $.files.splice(i, 1);
                $.fire('fileRemoved', $.files[i]);
            }
        }
    };
    $.getFromFileId = function (fileId) {
        var ret = false;
        $h.each($.files, function (f) {
            if (f.fileId == fileId) {
                ret = f;
            }
        });
        return ret;
    };
    $.getSize = function () {
        var totalSize = 0;
        $h.each($.files, function (file) {
            totalSize += file.size;
        });
        return totalSize;
    };
    $.handleDropEvent = function (e) {
        onDrop(e);
    };
    $.handleChangeEvent = function (e) {
        appendFilesFromFileList(e.target.files, e);
        e.target.value = '';
    };
    $.updateQuery = function (query) {
        $.opts.query = query;
    };

    /**
     * jack: 支持可恢复的断点续传功能（需要前端计算文件MD5值和后端服务支持）
     * 由于前端计算MD5耗性能，因此这里采用Worker线程池和任务队列方式来加速计算。
     */
    $.canResumableUpload = false;
    if ($.getOpt('resumableUpload') === true) {
        if (typeof window.Worker === 'undefined' || typeof window.Blob === 'undefined') {
            console.warn("Your browser does not supports resumable upload features, because web Worker or Blob not supported!");
            return;
        }
        $.canResumableUpload = true;
        $.md5WorkerUrl = window.URL.createObjectURL(new Blob([Resumable.MD5_HASHER], {type:"text/javascript"}));
        $.md5WorkerPool = new ResumableWorkerPool($.md5WorkerUrl, {
            maxSize: Math.min(5, typeof $.getOpt('maxFiles') !== 'undefined' ? $.getOpt('maxFiles') : 5)
        });
    }

    return this;
};

// 2024/04/16 jack 扩展：
Resumable.MIME_TYPES = {
    "abs": "audio/x-mpeg",
    "ai": "application/postscript",
    "aif": "audio/x-aiff",
    "aifc": "audio/x-aiff",
    "aiff": "audio/x-aiff",
    "aim": "application/x-aim",
    "art": "image/x-jg",
    "asf": "video/x-ms-asf",
    "asx": "video/x-ms-asf",
    "au": "audio/basic",
    "avi": "video/x-msvideo",
    "avx": "video/x-rad-screenplay",
    "bcpio": "application/x-bcpio",
    "bin": "application/octet-stream",
    "bmp": "image/bmp",
    "cdf": "application/x-cdf",
    "cer": "application/pkix-cert",
    "class": "application/java",
    "cpio": "application/x-cpio",
    "csh": "application/x-csh",
    "css": "text/css",
    "dib": "image/bmp",
    "doc": "application/msword",
    "dtd": "application/xml-dtd",
    "dv": "video/x-dv",
    "dvi": "application/x-dvi",
    "eot": "application/vnd.ms-fontobject",
    "eps": "application/postscript",
    "etx": "text/x-setext",
    "exe": "application/octet-stream",
    "gif": "image/gif",
    "gtar": "application/x-gtar",
    "gz": "application/x-gzip",
    "hdf": "application/x-hdf",
    "hqx": "application/mac-binhex40",
    "htc": "text/x-component",
    "htm": "text/html",
    "html": "text/html",
    "ief": "image/ief",
    "jad": "text/vnd.sun.j2me.app-descriptor",
    "jar": "application/java-archive",
    "java": "text/x-java-source",
    "jnlp": "application/x-java-jnlp-file",
    "jpe": "image/jpeg",
    "jpeg": "image/jpeg",
    "jpg": "image/jpeg",
    "js": "application/javascript",
    "jsf": "text/plain",
    "json": "application/json",
    "jspf": "text/plain",
    "kar": "audio/midi",
    "latex": "application/x-latex",
    "m3u": "audio/x-mpegurl",
    "mac": "image/x-macpaint",
    "man": "text/troff",
    "mathml": "application/mathml+xml",
    "me": "text/troff",
    "mid": "audio/midi",
    "midi": "audio/midi",
    "mif": "application/x-mif",
    "mov": "video/quicktime",
    "movie": "video/x-sgi-movie",
    "mp1": "audio/mpeg",
    "mp2": "audio/mpeg",
    "mp3": "audio/mpeg",
    "mp4": "video/mp4",
    "mpa": "audio/mpeg",
    "mpe": "video/mpeg",
    "mpeg": "video/mpeg",
    "mpega": "audio/x-mpeg",
    "mpg": "video/mpeg",
    "mpv2": "video/mpeg2",
    "ms": "application/x-wais-source",
    "nc": "application/x-netcdf",
    "oda": "application/oda",
    "odb": "application/vnd.oasis.opendocument.database",
    "odc": "application/vnd.oasis.opendocument.chart",
    "odf": "application/vnd.oasis.opendocument.formula",
    "odg": "application/vnd.oasis.opendocument.graphics",
    "odi": "application/vnd.oasis.opendocument.image",
    "odm": "application/vnd.oasis.opendocument.text-master",
    "odp": "application/vnd.oasis.opendocument.presentation",
    "ods": "application/vnd.oasis.opendocument.spreadsheet",
    "odt": "application/vnd.oasis.opendocument.text",
    "otg": "application/vnd.oasis.opendocument.graphics-template",
    "oth": "application/vnd.oasis.opendocument.text-web",
    "otp": "application/vnd.oasis.opendocument.presentation-template",
    "ots": "application/vnd.oasis.opendocument.spreadsheet-template",
    "ott": "application/vnd.oasis.opendocument.text-template",
    "ogx": "application/ogg",
    "ogv": "video/ogg",
    "oga": "audio/ogg",
    "ogg": "audio/ogg",
    "otf": "application/x-font-opentype",
    "spx": "audio/ogg",
    "flac": "audio/flac",
    "anx": "application/annodex",
    "axa": "audio/annodex",
    "axv": "video/annodex",
    "xspf": "application/xspf+xml",
    "pbm": "image/x-portable-bitmap",
    "pct": "image/pict",
    "pdf": "application/pdf",
    "pgm": "image/x-portable-graymap",
    "pic": "image/pict",
    "pict": "image/pict",
    "pls": "audio/x-scpls",
    "png": "image/png",
    "pnm": "image/x-portable-anymap",
    "pnt": "image/x-macpaint",
    "ppm": "image/x-portable-pixmap",
    "ppt": "application/vnd.ms-powerpoint",
    "pps": "application/vnd.ms-powerpoint",
    "ps": "application/postscript",
    "psd": "image/vnd.adobe.photoshop",
    "qt": "video/quicktime",
    "qti": "image/x-quicktime",
    "qtif": "image/x-quicktime",
    "ras": "image/x-cmu-raster",
    "rdf": "application/rdf+xml",
    "rgb": "image/x-rgb",
    "rm": "application/vnd.rn-realmedia",
    "roff": "text/troff",
    "rtf": "application/rtf",
    "rtx": "text/richtext",
    "sfnt": "application/font-sfnt",
    "sh": "application/x-sh",
    "shar": "application/x-shar",
    "sit": "application/x-stuffit",
    "snd": "audio/basic",
    "src": "application/x-wais-source",
    "sv4cpio": "application/x-sv4cpio",
    "sv4crc": "application/x-sv4crc",
    "svg": "image/svg+xml",
    "svgz": "image/svg+xml",
    "swf": "application/x-shockwave-flash",
    "t": "text/troff",
    "tar": "application/x-tar",
    "tcl": "application/x-tcl",
    "tex": "application/x-tex",
    "texi": "application/x-texinfo",
    "texinfo": "application/x-texinfo",
    "tif": "image/tiff",
    "tiff": "image/tiff",
    "tr": "text/troff",
    "tsv": "text/tab-separated-values",
    "ttf": "application/x-font-ttf",
    "txt": "text/plain",
    "ulw": "audio/basic",
    "ustar": "application/x-ustar",
    "vxml": "application/voicexml+xml",
    "xbm": "image/x-xbitmap",
    "xht": "application/xhtml+xml",
    "xhtml": "application/xhtml+xml",
    "xls": "application/vnd.ms-excel",
    "xml": "application/xml",
    "xpm": "image/x-xpixmap",
    "xsl": "application/xml",
    "xslt": "application/xslt+xml",
    "xul": "application/vnd.mozilla.xul+xml",
    "xwd": "image/x-xwindowdump",
    "vsd": "application/vnd.visio",
    "wasm": "application/wasm",
    "wav": "audio/x-wav",
    "wbmp": "image/vnd.wap.wbmp",
    "wml": "text/vnd.wap.wml",
    "wmlc": "application/vnd.wap.wmlc",
    "wmls": "text/vnd.wap.wmlsc",
    "wmlscriptc": "application/vnd.wap.wmlscriptc",
    "wmv": "video/x-ms-wmv",
    "woff": "application/font-woff",
    "woff2": "application/font-woff2",
    "wrl": "model/vrml",
    "wspolicy": "application/wspolicy+xml",
    "z": "application/x-compress",
    "zip": "application/zip"
};
Resumable.FILE_ICONS = {
    'file': '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"/><path d="M14 2v5a1 1 0 0 0 1 1h5M12 12v6M15 15l-3-3-3 3"/></svg>',
    'img': '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z"/><path d="M14 2v5a1 1 0 0 0 1 1h5"/><circle cx="10" cy="12" r="2"/><path d="M20 17l-1.296-1.296a2.41 2.41 0 0 0-3.408 0L9 22"/></svg>',
    'audio': '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M11.65 22H18a2 2 0 0 0 2-2V8a2.4 2.4 0 0 0-.706-1.706l-3.588-3.588A2.4 2.4 0 0 0 14 2H6a2 2 0 0 0-2 2v10.35"/><path d="M14 2v5a1 1 0 0 0 1 1h5M8 20v-7l3 1.474"/><circle cx="6" cy="20" r="2"/></svg>',
    'video': '<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 12V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.706.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2"/><path d="M14 2v5a1 1 0 0 0 1 1h5M10 17.843l3.033-1.755a.64.64 0 0 1 .967.56v4.704a.65.65 0 0 1-.967.56L10 20.157"/><rect width="7" height="6" x="3" y="16" rx="1"/></svg>'
};
/**
 * 使用方式：
 * 1. 使用内置提供的UI
 * var r = new Resumable({...});
 * r.initUI('domSelector'|HTMLElement);
 *
 * 2. 使用自定义的drop区域和浏览文件触发对象
 * var r = new Resumable({
 *     dropTarget: 'domSelector'|HTMLElement,
 *     browseTarget: 'domSelector'|HTMLElement,
 *     filesListTarget: 'domSelector'|HTMLElement
 * });
 * r.initUI();
 */
Resumable.prototype.initUI = function(container) {
    if (typeof container === 'string') {
        container = document.querySelector(container);
    }

    var r = this;
    r.uiDoms = {};
    r._AddedFileItems = {};

    if (!r.support) {
        container && (container.innerHTML = '<div style="font-weight:bold;color:#c87619;text-align:center;padding:10px 0;">当前浏览器不支持HTML5文件上传特性</div>');
        console.warn("当前浏览器不支持HTML5文件上传特性");
        return false;
    }

    var dropTargetEle = r.getOpt('dropTarget'), // 自定义的drop区域对象
        browseTargetEle = r.getOpt('browseTarget'), // 自定义的浏览文件触发对象
        filesListTarget = r.getOpt('filesListTarget'); // 自定义的文件列表展示区域对象
    if (typeof dropTargetEle === 'string') {
        dropTargetEle = document.querySelector(dropTargetEle);
    }
    if (typeof browseTargetEle === 'string') {
        browseTargetEle = document.querySelector(browseTargetEle);
    }
    if (typeof filesListTarget === 'string') {
        filesListTarget = document.querySelector(filesListTarget);
    }

    if (!document.getElementById('_resumable_ui_style_')) {
        var uiStyle = document.createElement('style');
        uiStyle.id = '_resumable_ui_style_';
        uiStyle.type = 'text/css';
        uiStyle.textContent = '.resum-drop {padding:20px;border:1px dashed #C5CBD3;color:#383E47;background-color:#E5E8EB;border-radius:5px;font-family:"Helvetica Neue", Arial, sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji";}' +
            '.resum-dragover {background-color:#F6F7F9;}'+
            '.resum-subtext{color:#738091;font-size:0.9em;}'+
            '.resum-browser {color:#2D72D2;} .resum-browser:hover {text-decoration:underline;}'+
            '.resum-files-list {max-height:300px;overflow-y:auto;}'+
            '.resum-files-list:not(:empty){margin:10px -20px -20px -20px; padding-bottom:10px; background-color:#fff; border-radius:0 0 5px 5px;}'+
            '.resum-file-item {font-size:0.85em; padding: 8px 8px;border-top:1px solid #DCE0E5;}'+
            '.resum-file-icon {width:24px;height:24px;} ' +
            '.resum-file-progress {height:4px;border-radius:2px;background-color:#E5E8EB;}'+
            '.resum-file-progress-indicator {border-radius:2px;background-color:#238551;}'+
            '.resum-file-progress-value {}'+
            '.resum-tip {padding:5px 10px;border-radius:5px;background-color:rgb(205,66,70);color:#fff;max-width:500px;cursor:default;box-shadow:inset 0 0 0 1px rgba(17,20,24,.2),0 2px 4px rgba(17,20,24,.2),0 8px 24px rgba(17,20,24,.2);}'+
            '.resum-file-actions {display:flex;align-items:center;}'+
            '.resum-file-actions > button {padding:2px;border:0 none;line-height:1.0;color:#404854;margin-left:2px;background-color:transparent;border-radius:3px;cursor:pointer;display:flex;align-items:center;justify-content:center;}'+
            '.resum-file-actions > button:hover {background-color:#eef0f2;}'+
            '.resum-file-actions > button svg:not([fill]) {fill:currentcolor;}'
        ;
        document.getElementsByTagName('head')[0].appendChild(uiStyle);
    }

    var uiDoms = r.uiDoms;
    var showTip = r.getOpt('showMessage') || function(){};

    if (!dropTargetEle && container) {
        container.innerHTML = '<div style="position:relative; height:100%;">' +
            '<div data-ref="dropper" class="resum-drop" tabindex="-1">' +
            '<div style="text-align:center;margin-bottom:10px;pointer-events:none;"><svg width="48" height="48" viewBox="0 0 20 20" class="resum-icon"><path fill="#8F99A8" d="M10.71 10.29c-.18-.18-.43-.29-.71-.29s-.53.11-.71.29l-3 3a1.003 1.003 0 001.42 1.42L9 13.41V19c0 .55.45 1 1 1s1-.45 1-1v-5.59l1.29 1.29c.18.19.43.3.71.3a1.003 1.003 0 00.71-1.71l-3-3zM15 4c-.12 0-.24.03-.36.04C13.83 1.69 11.62 0 9 0 5.69 0 3 2.69 3 6c0 .05.01.09.01.14A3.98 3.98 0 000 10c0 2.21 1.79 4 4 4 0-.83.34-1.58.88-2.12l3-3a2.993 2.993 0 014.24 0l3 3-.01.01c.52.52.85 1.23.87 2.02C18.28 13.44 20 11.42 20 9c0-2.76-2.24-5-5-5z" fill-rule="evenodd"></path></svg></div>' +
            '<div style="text-align:center;font-weight:400;line-height:2.0;font-size:1em;margin-bottom:10px;">拖放文件到这里，或 <div data-ref="browser" class="resum-browser" style="display:inline-block;cursor:pointer;">选择文件</div></div>' +
            (r.getOpt('fileTypes') && r.getOpt('fileTypes').length > 0 ? ('<div style="text-align:center;line-height:1.5;pointer-events:none;" class="resum-subtext resum-filetypes">支持文件类型：' + r.getOpt('fileTypes').join(', ') + '</div>') : '') +
            (r.getOpt('maxFileSize') && r.getOpt('maxFileSize') > 0 ? ('<div style="text-align:center;line-height:1.5;pointer-events:none;" class="resum-subtext resum-max-filesize">单文件最大：' + r.utils.formatSize(r.getOpt('maxFileSize')) + '</div>') : '') +
            '<div data-ref="filesList" class="resum-files-list"></div>' +
            '</div>' +
            '<div data-ref="tipOverlay" style="display:none;position:absolute;left:0;top:0;right:0;bottom:0;z-index:2;padding:0 10px;flex-direction:column;align-items:center;justify-content:center;">' +
            '<div data-ref="tipMsg" tabindex="-1" class="resum-tip"></div>' +
            '</div>' +
            '</div>';

        container.querySelectorAll('[data-ref]').forEach(function (e) {
            uiDoms[e.getAttribute("data-ref")] = e;
        });

        r.assignDrop(uiDoms['dropper']);
        r.assignBrowse(uiDoms['browser'], r.getOpt('directoryUpload'));

        var tipTimer = null;
        uiDoms['tipMsg'].addEventListener('focus', function() {
            (tipTimer !== null) && clearTimeout(tipTimer);
        });
        uiDoms['tipMsg'].addEventListener('blur', function() {
            (tipTimer !== null) && clearTimeout(tipTimer);
            tipTimer = setTimeout(function() {
                uiDoms['tipOverlay'].style.display = 'none';
            }, 3000);
        });

        showTip = function (msg) {
            (tipTimer !== null) && clearTimeout(tipTimer);
            uiDoms['tipOverlay'].style.display = 'flex';
            uiDoms['tipMsg'].innerText = msg || '';
            tipTimer = setTimeout(function() {
                uiDoms['tipOverlay'].style.display = 'none';
            }, 3000);
        };
    }

    // 如果自定义了，则使用自定义的
    if (dropTargetEle) {
        r.uiDoms['dropper'] = dropTargetEle;
        r.assignDrop(dropTargetEle);
    }
    if (browseTargetEle) {
        r.assignBrowse(browseTargetEle, r.getOpt('directoryUpload'));
    }
    if (filesListTarget) {
        uiDoms['filesList'] = filesListTarget;
    }

    r.defaults.maxFilesErrorCallback = function (files, errorCount) {
        showTip('请不要一次上传超过 ' + r.getOpt('maxFiles') + ' 个文件');
    };
    r.defaults.minFileSizeErrorCallback = function (file, errorCount) {
        showTip((file.fileName || file.name) + ' 文件太小，请上传大于等于 ' + r.utils.formatSize(r.getOpt('minFileSize')) + ' 大小的文件');
    };
    r.defaults.maxFileSizeErrorCallback = function (file, errorCount) {
        showTip((file.fileName || file.name) + ' 文件太大，单个文件不能超过 ' + r.utils.formatSize(r.getOpt('maxFileSize')) + ' 大小');
    };
    r.defaults.fileTypesErrorCallback = function (file, errorCount) {
        showTip((file.fileName || file.name) + ' 文件类型不被允许，允许类型：' + r.getOpt('fileTypes').join(', '));
    };

    var FileItem = function(file) {
        this.file = file;
        var ele = this.ele = document.createElement('div');
        var fileExt = 'file';
        var dotIndex = file.fileName.lastIndexOf('.');
        if (dotIndex != -1) {
            fileExt = file.fileName.substring(dotIndex + 1).toLowerCase();
        }
        if (/^(png|jpg|jpeg|gif|webp|svg|ico|bmp|tif|apng|avif|wmf|hdr|psd)$/i.test(fileExt)) {
            fileExt = 'img';
        } else if (/^(mp4|mov|wmv|flv|avi|webm|mkv|m4v|rm|rmvb|mpeg|mpg|mpe|3gp|f4v|dv|divx|m4r|m4a)$/i.test(fileExt)) {
            fileExt = 'video';
        } else if (/^(mp3|aac|ogg|wav|opus|flac|alac|ape|aac3)$/i.test(fileExt)) {
            fileExt = 'audio';
        }

        var fileIcon = (Resumable.FILE_ICONS[fileExt] || Resumable.FILE_ICONS['file']);
        ele.className = 'resum-file-item';
        ele.style.cssText = 'display:flex;align-items:center;';
        ele.innerHTML = '<div class="resum-file-icon" style="display:flex;align-items:center;justify-content:center;">'+ fileIcon +'</div>'+
            '<div style="flex:1;padding:0 10px;">' +
            '<div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:3px;">' +
            '<div style="padding:0 10px 0 5px;"><span class="resum-file-name">'+ file.fileName +'</span> <span class="resum-subtext" style="white-space:nowrap;">('+ file.readableSize +')</span></div>' +
            '<div data-ref="progressValue" class="resum-file-progress-value" style="white-space:nowrap;">0%</div>' +
            '</div>'+
            '<div class="resum-file-progress" style="position:relative;">' +
            '<div data-ref="progressIndicator" class="resum-file-progress-indicator" style="position:absolute;left:0;top:0;height:100%;width:0;font-size:0;"></div>' +
            '</div>'+
            '</div>'+
            '<div class="resum-file-actions">' +
            '<button type="button" data-ref="actionPause" class="resum-file-action-pause" title="暂停上传" style="display:none;"><svg width="16" height="16" viewBox="0 0 16 16"><path d="M6 3H4c-.55 0-1 .45-1 1v8c0 .55.45 1 1 1h2c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zm6 0h-2c-.55 0-1 .45-1 1v8c0 .55.45 1 1 1h2c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1z" fill-rule="evenodd"></path></svg></button>'+
            '<button type="button" data-ref="actionPlay" class="resum-file-action-play" title="继续上传" style="display:none;"><svg width="16" height="16" viewBox="0 0 16 16"><path d="M12 8c0-.35-.19-.64-.46-.82l.01-.02-6-4-.01.02A.969.969 0 005 3c-.55 0-1 .45-1 1v8c0 .55.45 1 1 1 .21 0 .39-.08.54-.18l.01.02 6-4-.01-.02c.27-.18.46-.47.46-.82z" fill-rule="evenodd"></path></svg></button>'+
            '<button type="button" data-ref="actionCancel" class="resum-file-action-cancel" title="取消上传"><svg width="16" height="16" viewBox="0 0 16 16"><path d="M9.41 8l3.29-3.29c.19-.18.3-.43.3-.71a1.003 1.003 0 00-1.71-.71L8 6.59l-3.29-3.3a1.003 1.003 0 00-1.42 1.42L6.59 8 3.3 11.29c-.19.18-.3.43-.3.71a1.003 1.003 0 001.71.71L8 9.41l3.29 3.29c.18.19.43.3.71.3a1.003 1.003 0 00.71-1.71L9.41 8z" fill-rule="evenodd"></path></svg></button>'+
            '<span data-ref="successTip" style="display:none;line-height:1.0;"><svg width="20" height="20" viewBox="0 0 20 20"><path fill="#238551" d="M15 5c-.28 0-.53.11-.71.29L8 11.59l-2.29-2.3a1.003 1.003 0 00-1.42 1.42l3 3c.18.18.43.29.71.29s.53-.11.71-.29l7-7A1.003 1.003 0 0015 5z" fill-rule="evenodd"></path></svg></span>'+
            '</div>';

        var doms = this.doms = {};
        ele.querySelectorAll('[data-ref]').forEach(function(e) {
            doms[e.getAttribute("data-ref")] = e;
        });

        doms['actionPause'].addEventListener('click', function() {
            this.pause();
        }.bind(this));

        doms['actionPlay'].addEventListener('click', function() {
            this.play();
        }.bind(this));

        doms['actionCancel'].addEventListener('click', function() {
            this.cancel();
        }.bind(this));
    };
    FileItem.prototype.updateProgress = function(val) {
        this.doms['progressIndicator'].style.width = val + '%';
        this.doms['progressValue'].innerText = val + '%';

        if (this.file.isComplete()) {
            this.doms['actionPause'].style.display = 'none';
            this.doms['actionPlay'].style.display = 'none';
            this.doms['actionCancel'].style.display = 'none';
            this.doms['successTip'].style.display = 'inline-block';
        }
        else if (this.file.isPaused()) {
            this.doms['actionPause'].style.display = 'none';
            this.doms['actionPlay'].style.display = 'inline-block';
            this.doms['progressValue'].innerText = '已暂停';
        }
        else if (this.file.isUploading()) {
            this.doms['actionPlay'].style.display = 'none';
            this.doms['actionPause'].style.display = 'inline-block';
        }
    };
    FileItem.prototype.pause = function() {
        this.file.pause(true);
        this.doms['actionPause'].style.display = 'none';
        this.doms['actionPlay'].style.display = 'inline-block';
        this.doms['progressValue'].innerText = '已暂停';
    };
    FileItem.prototype.play = function() {
        this.file.pause(false);
        this.file.upload();
        this.doms['actionPlay'].style.display = 'none';
        this.doms['actionPause'].style.display = 'inline-block';
        this.doms['progressValue'].innerText = '--';
    };
    FileItem.prototype.cancel = function() {
        this.file.cancel();
        this.ele.parentNode && this.ele.parentNode.removeChild(this.ele);
        r._AddedFileItems[this.file.fileId] = null;
        delete r._AddedFileItems[this.file.fileId];
    };

    r.on('filesAdded', function (newFiles, skippedFiles) {
        if (newFiles && newFiles.length > 0) {
            if (r.uiDoms['filesList']) {
                newFiles.forEach(function(f) {
                    var fitem = new FileItem(f);
                    r._AddedFileItems[f.fileId] = fitem;
                    r.uiDoms['filesList'].appendChild(fitem.ele);
                });
            }

            if (r.getOpt('autoUpload') === true) {
                r.upload();
            }
        }
    });
    r.on('fileProgress', function (file, msg) {
        r._AddedFileItems[file.fileId] && (r._AddedFileItems[file.fileId].updateProgress(Math.max(1, Math.round(file.progress() * 100))));
    });
    r.on('fileRemoved', function(file) {
        r._AddedFileItems[file.fileId] && r._AddedFileItems[file.fileId].cancel();
    });
    r.on('fileError', function (file, msg) {
        showTip(file.fileName + ' 上传失败：'+ (msg || '未知原因'));
    });
    r.on('error', function (msg, file) {
        showTip(msg);
    });

    // 支持粘贴获取剪切板中的文件对象（包括：截图和文件）
    r._onFilePaste = function(evt) {
        // 判断当前触发的元素是不是 resumable的dropTarget元素
        var triggerEle = evt.target;
        if (!triggerEle) {
            return;
        }
        var dropTarget = r.uiDoms['dropper'];
        if (!dropTarget) {
            return;
        }
        var pasteInDropTarget = false;
        while ('BODY' !== triggerEle.tagName) {
            if (triggerEle === dropTarget) {
                pasteInDropTarget = true;
                break;
            }
            triggerEle = triggerEle.parentNode;
        }
        if (!pasteInDropTarget) {
            return;
        }

        var items = (evt.clipboardData || evt.originalEvent.clipboardData).items;
        if (!items || items.length === 0) {
            return;
        }
        var files = [];
        for (var i = 0, len = items.length; i < len; ++i) {
            var item = items[i];
            if ('file' !== item.kind) {
                continue;
            }
            var f = item.getAsFile();
            // 判断是不是截屏等非实体文件：修改时间值基本上是当前瞬时时间值
            var lastModified = f.lastModified, nowTime = new Date().getTime();
            if (!lastModified || (nowTime - lastModified < 500)) {
                // 重命名剪切板中的临时文件名，避免文件名冲突
                var fname = f.fileName || f.name, fext = '';
                var lastDotIndex = fname.lastIndexOf('.');
                if (lastDotIndex !== -1) {
                    fext = fname.substring(lastDotIndex);
                    fname = fname.substring(0, lastDotIndex);
                }
                f.newName = fname + '-' + (f.lastModified || f.size) + fext;
            }
            files.push(f);
        }
        if (files.length > 0) {
            r.addFiles(files, evt);
            files = null;
        }
    };

    if (r.uiDoms['dropper'] && r.getOpt('pasteUpload') === true) {
        document.addEventListener('paste', r._onFilePaste);
    }

};

Resumable.prototype._calcFileMD5 = function(resumFile) {
    if (!this.canResumableUpload) {
        return;
    }

    this.md5WorkerPool.submit((function(rfile) {
        return function(agent) {
            var stime = Date.now();
            var file = rfile.file;
            var fileSize = rfile.size;
            var fileName = rfile.fileName;
            var lastModified = rfile.lastModified;
            var maxChunkSize = 1 * 1024 * 1024; // 每次处理数据块的最大值1MB
            var chunksPerCycle = 50;  // 每个计算周期中处理的数据块数
            var totalChunks = Math.ceil(fileSize / maxChunkSize);
            var totalCalc = true; // 是否全量计算MD5
            // 为了加速大文件的MD5计算速度，采用加速策略：
            // a. <= 20MB 的全量计算
            // b. > 20MB 的抽样5段(前、后、中间3段)+文件修改时间计算即可
            var needHashChunks = [];
            if (fileSize <= 20 * 1024 * 1024) {
                for (var c = 1; c <= totalChunks; ++c) {
                    needHashChunks.push(c);
                }
            } else {
                totalCalc = false;
                needHashChunks = [1, Math.round(totalChunks * 0.4), Math.round(totalChunks * 0.6), Math.round(totalChunks * 0.8), totalChunks];
            }

            var fileReader = new FileReader();
            var blobSlice = File.prototype.slice || File.prototype.webkitSlice || File.prototype.mozSlice;
            var done = function() {
                file = undefined;
                try {
                    agent.done();
                } catch (e) {}
                try {
                    fileReader.abort();
                } catch (e) {}
            };
            // 分片送往worker处理
            var currentChunk = needHashChunks.shift();
            var isReadingFile = false;
            try {
                // 处理数据块
                var processChunk = function(chunkNo) {
                    isReadingFile = true;
                    var start = (chunkNo - 1) * maxChunkSize;
                    var end = Math.min(chunkNo * maxChunkSize, fileSize);
                    fileReader.readAsArrayBuffer(blobSlice.call(file, start, end));
                };

                fileReader.onload = function() {
                    agent.postMessage({"dataBuffer": fileReader.result, "status": 'ing'}, [fileReader.result]);
                    isReadingFile = false;

                    // 获取下一个块进行计算
                    currentChunk = needHashChunks.shift();
                    if (currentChunk) {
                        processChunk(currentChunk);
                    } else {
                        try {
                            fileReader.abort();
                        } catch(e) {}

                        // 文件块发送完，通知worker给出最终的md5值
                        if (totalCalc) {
                            agent.postMessage({"status": "end"});
                        } else {
                            // 对于抽样计算，最后将文件大小和文件最后一次修改时间进行追加计算，最大程度上减少MD5冲突
                            var endInfo = '{fileSize:'+ fileSize +', lastModified:'+ lastModified +'}';
                            var strReader = new FileReader();
                            strReader.onload = function() {
                                agent.postMessage({"dataBuffer": strReader.result, "status": "end"}, [strReader.result]);
                                try {
                                    strReader.abort();
                                } catch(e) {}
                            };
                            strReader.readAsArrayBuffer(new Blob([endInfo], {type: 'text/plain'}));
                        }
                    }

                    // 如果采用全量计算，则是下面的计算方式
                    /*currentChunk += 1;
                    if (currentChunk <= totalChunks) {
                        // 延迟下，让出空闲给UI主线程执行
                        if (currentChunk % chunksPerCycle === 0) {
                            setTimeout(() => processChunk(currentChunk), 10);
                        } else {
                            processChunk(currentChunk);
                        }
                    } else {
                        // 文件块发送完，通知worker给出最终的md5值
                        agent.postMessage({"status": "end"});
                        try {
                            fileReader.abort();
                        }catch(e){}
                    }*/
                };
                fileReader.onerror = function(e) {
                    console.error(e);
                    done();
                    return;
                };

                agent.onmessage(function(e) {
                    var data = e.data;
                    var status = data.status, error = data.error;
                    // Worker通知准备好了，发送第一块数据
                    if ('ready' === status) {
                        if (!isReadingFile && currentChunk === 1) {
                            processChunk(1);
                        }
                        return;
                    }

                    // MD5计算结束
                    if ('end' === status) {
                        rfile.setMD5(data.md5);
                        done();
                        return;
                    }

                    console.error(data);
                    done();
                });
                agent.onerror(function(e) {
                    console.error(e);
                    done();
                });

                // 通知worker开始计算了
                agent.postMessage({"status": "start"});

            } catch (e) {
                console.error(e);
                done();
            }
        };

    })(resumFile));
};

Resumable.prototype.destroy = function() {
    if (this.canResumableUpload) {
        this.canResumableUpload = false;
        this.md5WorkerPool.close();
        try {
            window.URL.revokeObjectURL(this.md5WorkerUrl);
            this.md5WorkerUrl = undefined;
        } catch (e) {
            console.warn(e);
        }
    }

    if ((typeof this._onFilePaste === 'function') && (this.getOpt('pasteUpload') === true)) {
        document.removeEventListener('paste', this._onFilePaste);
    }
};

/**
 * 先进先出任务队列。
 */
function ResumableQueue() {
    this.items = [];
}
ResumableQueue.prototype.add = function(ele) {
    this.items.push(ele);
};
// 获取并移除此队列的头
ResumableQueue.prototype.pop = function() {
    return this.items.shift();
};
// 从队列中移除某个元素
ResumableQueue.prototype.remove = function(ele) {
    for (var i = 0, len = this.items.length; i < len; i++) {
        if (this.items[i] === ele) {
            this.items.splice(i, 1);
            return ele;
        }
    }
    return null;
};
ResumableQueue.prototype.size = function() {
    return this.items.length;
};
ResumableQueue.prototype.isEmpty = function() {
    return this.items.length === 0;
};
ResumableQueue.prototype.clear = function() {
    return this.items = [];
};

/**
 * Worker代理。
 * @param worker Worker对象
 */
function ResumableWorkerAgent(worker) {
    this._worker = worker;
}
ResumableWorkerAgent.prototype.postMessage = function() {
    this._worker.postMessage.apply(this._worker, arguments);
};
ResumableWorkerAgent.prototype.onmessage = function(func) {
    if (typeof func === 'function') {
        this._worker.onmessage = func;
    }
};
ResumableWorkerAgent.prototype.onerror = function(func) {
    if (typeof func === 'function') {
        this._worker.onerror = func;
    }
};
ResumableWorkerAgent.destroy = function(agent) {
    if (!agent) {
        return;
    }
    var worker = agent._worker;
    worker.onmessage = undefined;
    worker.onerror = undefined;
    agent._worker = undefined;
    return worker;
};

/**
 * Web Worker线程池。
 * @param url 用于Worker初始化的脚本URL
 * @param opts 可选的配置参数
 */
function ResumableWorkerPool(url, opts) {
    this.options = Object.assign({
        maxSize: 3
    }, opts || {});
    this.url = url;
    this.idlePool = new ResumableQueue(); // 空闲的Worker池
    this.busyPool = new ResumableQueue(); // 正在执行任务的Worker池
    this.queue = new ResumableQueue(); // 正在等待执行的任务队列
    this.maxSize = Math.max(1, this.options.maxSize); // 在线程池中所维护的 worker 的最大数量
    this.size = 0;
    // 先默认创建一个
    this._createWorker();
}
/**
 * 提交任务执行，当有一个空闲的worker时，会让该worker处理此任务。
 * @param {function} taskHandler 当有一个空间的worker可以用于执行此任务时会调用该函数，
 *      在函数内可以向worker发送消息，并监听worker返回的消息和异常，当使用完成后，需要调用done()
 *      函数通知线程池该任务执行完毕。
 * @return {function} deregistration 若该任务还未被受理，则调用该函数可以取消登记；
 *      若该登记已经受理或受理完毕，调用该函数则无任何作用
 */
ResumableWorkerPool.prototype.submit = function(taskHandler) {
    this.queue.add(taskHandler);
    this._execute();

    return (function() {
        this.queue.remove(taskHandler);
    }).bind(this);
};
// 关闭线程池
ResumableWorkerPool.prototype.close = function() {
    this.queue.clear();
    while (!this.idlePool.isEmpty()) {
        try { this.idlePool.pop().terminate(); } catch (e) {}
    }
    while (!this.busyPool.isEmpty()) {
        try { this.busyPool.pop().terminate(); } catch (e) {}
    }
    this.idlePool.clear();
    this.busyPool.clear();
    this.size = 0;
};
/**
 * 使用一个空闲中的worker处理任务队列中的一个任务，
 * 若当前没有空闲的worker或任务队列空，则不做任何操作。
 */
ResumableWorkerPool.prototype._execute = function() {
    if (this.queue.isEmpty()) {
        return;
    }
    var worker = this._getWorker();
    // 没有空闲Worker
    if (!worker) {
        return;
    }
    var task = this.queue.pop();
    if (!task) {
        this._recycleWorker(worker);
        return;
    }

    var agent = new ResumableWorkerAgent(worker);
    // 增加一个done用于任务执行完毕后调用释放worker和相关资源
    agent.done = function() {
        ResumableWorkerAgent.destroy(agent);
        this._recycleWorker(worker);
    }.bind(this);
    // 执行任务
    task.call(agent, agent);
};
ResumableWorkerPool.prototype._createWorker = function() {
    // 如果创建的Worker数量已经达到上限，则不再创建
    if (this.size >= this.maxSize) {
        return null;
    }
    var w = new Worker(this.url);
    this.idlePool.add(w);
    this.size++;
    return w;
};
/**
 * 返回一个空闲的worker，同时将其从空闲池移动到工作池中
 */
ResumableWorkerPool.prototype._getWorker = function() {
    if (this.idlePool.isEmpty()) {
        this._createWorker();
    }
    var worker = this.idlePool.pop();
    if (worker) {
        this.busyPool.add(worker);
    }
    return worker;
};
/**
 * 释放一个worker，此worker将从工作池中移回到空闲池中，便于下次使用。
 * @param worker Worker对象
 */
ResumableWorkerPool.prototype._recycleWorker = function(worker) {
    this.busyPool.remove(worker);
    this.idlePool.add(worker);
    // Worker空闲回收后尝试执行下一个任务
    this._execute();
};

/**
 * 使用spark-md5和hash-wasm进行文件MD5计算
 */
Resumable.MD5_HASHER =
    'var SparkMD5; (function(factory){SparkMD5=factory()})(function(undefined){"use strict";var add32=function(a,b){return a+b&4294967295},hex_chr=["0","1","2","3","4","5","6","7","8","9","a","b","c","d","e","f"];function cmn(q,a,b,x,s,t){a=add32(add32(a,q),add32(x,t));return add32(a<<s|a>>>32-s,b)}function md5cycle(x,k){var a=x[0],b=x[1],c=x[2],d=x[3];a+=(b&c|~b&d)+k[0]-680876936|0;a=(a<<7|a>>>25)+b|0;d+=(a&b|~a&c)+k[1]-389564586|0;d=(d<<12|d>>>20)+a|0;c+=(d&a|~d&b)+k[2]+606105819|0;c=(c<<17|c>>>15)+d|0;b+=(c&d|~c&a)+k[3]-1044525330|0;b=(b<<22|b>>>10)+c|0;a+=(b&c|~b&d)+k[4]-176418897|0;a=(a<<7|a>>>25)+b|0;d+=(a&b|~a&c)+k[5]+1200080426|0;d=(d<<12|d>>>20)+a|0;c+=(d&a|~d&b)+k[6]-1473231341|0;c=(c<<17|c>>>15)+d|0;b+=(c&d|~c&a)+k[7]-45705983|0;b=(b<<22|b>>>10)+c|0;a+=(b&c|~b&d)+k[8]+1770035416|0;a=(a<<7|a>>>25)+b|0;d+=(a&b|~a&c)+k[9]-1958414417|0;d=(d<<12|d>>>20)+a|0;c+=(d&a|~d&b)+k[10]-42063|0;c=(c<<17|c>>>15)+d|0;b+=(c&d|~c&a)+k[11]-1990404162|0;b=(b<<22|b>>>10)+c|0;a+=(b&c|~b&d)+k[12]+1804603682|0;a=(a<<7|a>>>25)+b|0;d+=(a&b|~a&c)+k[13]-40341101|0;d=(d<<12|d>>>20)+a|0;c+=(d&a|~d&b)+k[14]-1502002290|0;c=(c<<17|c>>>15)+d|0;b+=(c&d|~c&a)+k[15]+1236535329|0;b=(b<<22|b>>>10)+c|0;a+=(b&d|c&~d)+k[1]-165796510|0;a=(a<<5|a>>>27)+b|0;d+=(a&c|b&~c)+k[6]-1069501632|0;d=(d<<9|d>>>23)+a|0;c+=(d&b|a&~b)+k[11]+643717713|0;c=(c<<14|c>>>18)+d|0;b+=(c&a|d&~a)+k[0]-373897302|0;b=(b<<20|b>>>12)+c|0;a+=(b&d|c&~d)+k[5]-701558691|0;a=(a<<5|a>>>27)+b|0;d+=(a&c|b&~c)+k[10]+38016083|0;d=(d<<9|d>>>23)+a|0;c+=(d&b|a&~b)+k[15]-660478335|0;c=(c<<14|c>>>18)+d|0;b+=(c&a|d&~a)+k[4]-405537848|0;b=(b<<20|b>>>12)+c|0;a+=(b&d|c&~d)+k[9]+568446438|0;a=(a<<5|a>>>27)+b|0;d+=(a&c|b&~c)+k[14]-1019803690|0;d=(d<<9|d>>>23)+a|0;c+=(d&b|a&~b)+k[3]-187363961|0;c=(c<<14|c>>>18)+d|0;b+=(c&a|d&~a)+k[8]+1163531501|0;b=(b<<20|b>>>12)+c|0;a+=(b&d|c&~d)+k[13]-1444681467|0;a=(a<<5|a>>>27)+b|0;d+=(a&c|b&~c)+k[2]-51403784|0;d=(d<<9|d>>>23)+a|0;c+=(d&b|a&~b)+k[7]+1735328473|0;c=(c<<14|c>>>18)+d|0;b+=(c&a|d&~a)+k[12]-1926607734|0;b=(b<<20|b>>>12)+c|0;a+=(b^c^d)+k[5]-378558|0;a=(a<<4|a>>>28)+b|0;d+=(a^b^c)+k[8]-2022574463|0;d=(d<<11|d>>>21)+a|0;c+=(d^a^b)+k[11]+1839030562|0;c=(c<<16|c>>>16)+d|0;b+=(c^d^a)+k[14]-35309556|0;b=(b<<23|b>>>9)+c|0;a+=(b^c^d)+k[1]-1530992060|0;a=(a<<4|a>>>28)+b|0;d+=(a^b^c)+k[4]+1272893353|0;d=(d<<11|d>>>21)+a|0;c+=(d^a^b)+k[7]-155497632|0;c=(c<<16|c>>>16)+d|0;b+=(c^d^a)+k[10]-1094730640|0;b=(b<<23|b>>>9)+c|0;a+=(b^c^d)+k[13]+681279174|0;a=(a<<4|a>>>28)+b|0;d+=(a^b^c)+k[0]-358537222|0;d=(d<<11|d>>>21)+a|0;c+=(d^a^b)+k[3]-722521979|0;c=(c<<16|c>>>16)+d|0;b+=(c^d^a)+k[6]+76029189|0;b=(b<<23|b>>>9)+c|0;a+=(b^c^d)+k[9]-640364487|0;a=(a<<4|a>>>28)+b|0;d+=(a^b^c)+k[12]-421815835|0;d=(d<<11|d>>>21)+a|0;c+=(d^a^b)+k[15]+530742520|0;c=(c<<16|c>>>16)+d|0;b+=(c^d^a)+k[2]-995338651|0;b=(b<<23|b>>>9)+c|0;a+=(c^(b|~d))+k[0]-198630844|0;a=(a<<6|a>>>26)+b|0;d+=(b^(a|~c))+k[7]+1126891415|0;d=(d<<10|d>>>22)+a|0;c+=(a^(d|~b))+k[14]-1416354905|0;c=(c<<15|c>>>17)+d|0;b+=(d^(c|~a))+k[5]-57434055|0;b=(b<<21|b>>>11)+c|0;a+=(c^(b|~d))+k[12]+1700485571|0;a=(a<<6|a>>>26)+b|0;d+=(b^(a|~c))+k[3]-1894986606|0;d=(d<<10|d>>>22)+a|0;c+=(a^(d|~b))+k[10]-1051523|0;c=(c<<15|c>>>17)+d|0;b+=(d^(c|~a))+k[1]-2054922799|0;b=(b<<21|b>>>11)+c|0;a+=(c^(b|~d))+k[8]+1873313359|0;a=(a<<6|a>>>26)+b|0;d+=(b^(a|~c))+k[15]-30611744|0;d=(d<<10|d>>>22)+a|0;c+=(a^(d|~b))+k[6]-1560198380|0;c=(c<<15|c>>>17)+d|0;b+=(d^(c|~a))+k[13]+1309151649|0;b=(b<<21|b>>>11)+c|0;a+=(c^(b|~d))+k[4]-145523070|0;a=(a<<6|a>>>26)+b|0;d+=(b^(a|~c))+k[11]-1120210379|0;d=(d<<10|d>>>22)+a|0;c+=(a^(d|~b))+k[2]+718787259|0;c=(c<<15|c>>>17)+d|0;b+=(d^(c|~a))+k[9]-343485551|0;b=(b<<21|b>>>11)+c|0;x[0]=a+x[0]|0;x[1]=b+x[1]|0;x[2]=c+x[2]|0;x[3]=d+x[3]|0}function md5blk(s){var md5blks=[],i;for(i=0;i<64;i+=4){md5blks[i>>2]=s.charCodeAt(i)+(s.charCodeAt(i+1)<<8)+(s.charCodeAt(i+2)<<16)+(s.charCodeAt(i+3)<<24)}return md5blks}function md5blk_array(a){var md5blks=[],i;for(i=0;i<64;i+=4){md5blks[i>>2]=a[i]+(a[i+1]<<8)+(a[i+2]<<16)+(a[i+3]<<24)}return md5blks}function md51(s){var n=s.length,state=[1732584193,-271733879,-1732584194,271733878],i,length,tail,tmp,lo,hi;for(i=64;i<=n;i+=64){md5cycle(state,md5blk(s.substring(i-64,i)))}s=s.substring(i-64);length=s.length;tail=[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0];for(i=0;i<length;i+=1){tail[i>>2]|=s.charCodeAt(i)<<(i%4<<3)}tail[i>>2]|=128<<(i%4<<3);if(i>55){md5cycle(state,tail);for(i=0;i<16;i+=1){tail[i]=0}}tmp=n*8;tmp=tmp.toString(16).match(/(.*?)(.{0,8})$/);lo=parseInt(tmp[2],16);hi=parseInt(tmp[1],16)||0;tail[14]=lo;tail[15]=hi;md5cycle(state,tail);return state}function md51_array(a){var n=a.length,state=[1732584193,-271733879,-1732584194,271733878],i,length,tail,tmp,lo,hi;for(i=64;i<=n;i+=64){md5cycle(state,md5blk_array(a.subarray(i-64,i)))}a=i-64<n?a.subarray(i-64):new Uint8Array(0);length=a.length;tail=[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0];for(i=0;i<length;i+=1){tail[i>>2]|=a[i]<<(i%4<<3)}tail[i>>2]|=128<<(i%4<<3);if(i>55){md5cycle(state,tail);for(i=0;i<16;i+=1){tail[i]=0}}tmp=n*8;tmp=tmp.toString(16).match(/(.*?)(.{0,8})$/);lo=parseInt(tmp[2],16);hi=parseInt(tmp[1],16)||0;tail[14]=lo;tail[15]=hi;md5cycle(state,tail);return state}function rhex(n){var s="",j;for(j=0;j<4;j+=1){s+=hex_chr[n>>j*8+4&15]+hex_chr[n>>j*8&15]}return s}function hex(x){var i;for(i=0;i<x.length;i+=1){x[i]=rhex(x[i])}return x.join("")}if(hex(md51("hello"))!=="5d41402abc4b2a76b9719d911017c592"){add32=function(x,y){var lsw=(x&65535)+(y&65535),msw=(x>>16)+(y>>16)+(lsw>>16);return msw<<16|lsw&65535}}if(typeof ArrayBuffer!=="undefined"&&!ArrayBuffer.prototype.slice){(function(){function clamp(val,length){val=val|0||0;if(val<0){return Math.max(val+length,0)}return Math.min(val,length)}ArrayBuffer.prototype.slice=function(from,to){var length=this.byteLength,begin=clamp(from,length),end=length,num,target,targetArray,sourceArray;if(to!==undefined){end=clamp(to,length)}if(begin>end){return new ArrayBuffer(0)}num=end-begin;target=new ArrayBuffer(num);targetArray=new Uint8Array(target);sourceArray=new Uint8Array(this,begin,num);targetArray.set(sourceArray);return target}})()}function toUtf8(str){if(/[\u0080-\uFFFF]/.test(str)){str=unescape(encodeURIComponent(str))}return str}function utf8Str2ArrayBuffer(str,returnUInt8Array){var length=str.length,buff=new ArrayBuffer(length),arr=new Uint8Array(buff),i;for(i=0;i<length;i+=1){arr[i]=str.charCodeAt(i)}return returnUInt8Array?arr:buff}function arrayBuffer2Utf8Str(buff){return String.fromCharCode.apply(null,new Uint8Array(buff))}function concatenateArrayBuffers(first,second,returnUInt8Array){var result=new Uint8Array(first.byteLength+second.byteLength);result.set(new Uint8Array(first));result.set(new Uint8Array(second),first.byteLength);return returnUInt8Array?result:result.buffer}function hexToBinaryString(hex){var bytes=[],length=hex.length,x;for(x=0;x<length-1;x+=2){bytes.push(parseInt(hex.substr(x,2),16))}return String.fromCharCode.apply(String,bytes)}function SparkMD5(){this.reset()}SparkMD5.prototype.append=function(str){this.appendBinary(toUtf8(str));return this};SparkMD5.prototype.appendBinary=function(contents){this._buff+=contents;this._length+=contents.length;var length=this._buff.length,i;for(i=64;i<=length;i+=64){md5cycle(this._hash,md5blk(this._buff.substring(i-64,i)))}this._buff=this._buff.substring(i-64);return this};SparkMD5.prototype.end=function(raw){var buff=this._buff,length=buff.length,i,tail=[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],ret;for(i=0;i<length;i+=1){tail[i>>2]|=buff.charCodeAt(i)<<(i%4<<3)}this._finish(tail,length);ret=hex(this._hash);if(raw){ret=hexToBinaryString(ret)}this.reset();return ret};SparkMD5.prototype.reset=function(){this._buff="";this._length=0;this._hash=[1732584193,-271733879,-1732584194,271733878];return this};SparkMD5.prototype.getState=function(){return{buff:this._buff,length:this._length,hash:this._hash.slice()}};SparkMD5.prototype.setState=function(state){this._buff=state.buff;this._length=state.length;this._hash=state.hash;return this};SparkMD5.prototype.destroy=function(){delete this._hash;delete this._buff;delete this._length};SparkMD5.prototype._finish=function(tail,length){var i=length,tmp,lo,hi;tail[i>>2]|=128<<(i%4<<3);if(i>55){md5cycle(this._hash,tail);for(i=0;i<16;i+=1){tail[i]=0}}tmp=this._length*8;tmp=tmp.toString(16).match(/(.*?)(.{0,8})$/);lo=parseInt(tmp[2],16);hi=parseInt(tmp[1],16)||0;tail[14]=lo;tail[15]=hi;md5cycle(this._hash,tail)};SparkMD5.hash=function(str,raw){return SparkMD5.hashBinary(toUtf8(str),raw)};SparkMD5.hashBinary=function(content,raw){var hash=md51(content),ret=hex(hash);return raw?hexToBinaryString(ret):ret};SparkMD5.ArrayBuffer=function(){this.reset()};SparkMD5.ArrayBuffer.prototype.append=function(arr){var buff=concatenateArrayBuffers(this._buff.buffer,arr,true),length=buff.length,i;this._length+=arr.byteLength;for(i=64;i<=length;i+=64){md5cycle(this._hash,md5blk_array(buff.subarray(i-64,i)))}this._buff=i-64<length?new Uint8Array(buff.buffer.slice(i-64)):new Uint8Array(0);return this};SparkMD5.ArrayBuffer.prototype.end=function(raw){var buff=this._buff,length=buff.length,tail=[0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],i,ret;for(i=0;i<length;i+=1){tail[i>>2]|=buff[i]<<(i%4<<3)}this._finish(tail,length);ret=hex(this._hash);if(raw){ret=hexToBinaryString(ret)}this.reset();return ret};SparkMD5.ArrayBuffer.prototype.reset=function(){this._buff=new Uint8Array(0);this._length=0;this._hash=[1732584193,-271733879,-1732584194,271733878];return this};SparkMD5.ArrayBuffer.prototype.getState=function(){var state=SparkMD5.prototype.getState.call(this);state.buff=arrayBuffer2Utf8Str(state.buff);return state};SparkMD5.ArrayBuffer.prototype.setState=function(state){state.buff=utf8Str2ArrayBuffer(state.buff,true);return SparkMD5.prototype.setState.call(this,state)};SparkMD5.ArrayBuffer.prototype.destroy=SparkMD5.prototype.destroy;SparkMD5.ArrayBuffer.prototype._finish=SparkMD5.prototype._finish;SparkMD5.ArrayBuffer.hash=function(arr,raw){var hash=md51_array(new Uint8Array(arr)),ret=hex(hash);return raw?hexToBinaryString(ret):ret};return SparkMD5});'+
    'var hashwasm; !function(A,B){B(hashwasm=A.hashwasm||{})}(this,(function(A){"use strict";function B(A,B,e,t){return new(e||(e=Promise))((function(i,I){function o(A){try{n(t.next(A))}catch(A){I(A)}}function a(A){try{n(t.throw(A))}catch(A){I(A)}}function n(A){var B;A.done?i(A.value):(B=A.value,B instanceof e?B:new e((function(A){A(B)}))).then(o,a)}n((t=t.apply(A,B||[])).next())}))}"function"==typeof SuppressedError&&SuppressedError;class e{constructor(){this.mutex=Promise.resolve()}lock(){let A=()=>{};return this.mutex=this.mutex.then((()=>new Promise(A))),new Promise((B=>{A=B}))}dispatch(A){return B(this,void 0,void 0,(function*(){const B=yield this.lock();try{return yield Promise.resolve(A())}finally{B()}}))}}var t;const i="undefined"!=typeof globalThis?globalThis:"undefined"!=typeof self?self:"undefined"!=typeof window?window:global,I=null!==(t=i.Buffer)&&void 0!==t?t:null,o=i.TextEncoder?new i.TextEncoder:null;function a(A,B){return(15&A)+(A>>6|A>>3&8)<<4|(15&B)+(B>>6|B>>3&8)}const n="a".charCodeAt(0)-10,g="0".charCodeAt(0);function Q(A,B,e){let t=0;for(let i=0;i<e;i++){let e=B[i]>>>4;A[t++]=e>9?e+n:e+g,e=15&B[i],A[t++]=e>9?e+n:e+g}return String.fromCharCode.apply(null,A)}const r=null!==I?A=>{if("string"==typeof A){const B=I.from(A,"utf8");return new Uint8Array(B.buffer,B.byteOffset,B.length)}if(I.isBuffer(A))return new Uint8Array(A.buffer,A.byteOffset,A.length);if(ArrayBuffer.isView(A))return new Uint8Array(A.buffer,A.byteOffset,A.byteLength);throw new Error("Invalid data type!")}:A=>{if("string"==typeof A)return o.encode(A);if(ArrayBuffer.isView(A))return new Uint8Array(A.buffer,A.byteOffset,A.byteLength);throw new Error("Invalid data type!")},E="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/",c=new Uint8Array(256);for(let A=0;A<E.length;A++)c[E.charCodeAt(A)]=A;function s(A){const B=function(A){let B=Math.floor(.75*A.length);const e=A.length;return"="===A[e-1]&&(B-=1,"="===A[e-2]&&(B-=1)),B}(A),e=A.length,t=new Uint8Array(B);let i=0;for(let B=0;B<e;B+=4){const e=c[A.charCodeAt(B)],I=c[A.charCodeAt(B+1)],o=c[A.charCodeAt(B+2)],a=c[A.charCodeAt(B+3)];t[i]=e<<2|I>>4,i+=1,t[i]=(15&I)<<4|o>>2,i+=1,t[i]=(3&o)<<6|63&a,i+=1}return t}const C=16384,h=new e,f=new Map;function l(A,e){return B(this,void 0,void 0,(function*(){let t=null,i=null,I=!1;if("undefined"==typeof WebAssembly)throw new Error("WebAssembly is not supported in this environment!");const o=()=>new DataView(t.exports.memory.buffer).getUint32(t.exports.STATE_SIZE,!0),n=h.dispatch((()=>B(this,void 0,void 0,(function*(){if(!f.has(A.name)){const B=s(A.data),e=WebAssembly.compile(B);f.set(A.name,e)}const B=yield f.get(A.name);t=yield WebAssembly.instantiate(B,{})})))),g=(A=null)=>{I=!0,t.exports.Hash_Init(A)},E=A=>{if(!I)throw new Error("update() called before init()");(A=>{let B=0;for(;B<A.length;){const e=A.subarray(B,B+C);B+=e.length,i.set(e),t.exports.Hash_Update(e.length)}})(r(A))},c=new Uint8Array(2*e),l=(A,B=null)=>{if(!I)throw new Error("digest() called before init()");return I=!1,t.exports.Hash_Final(B),"binary"===A?i.slice(0,e):Q(c,i,e)},p=A=>"string"==typeof A?A.length<4096:A.byteLength<C;let y=p;switch(A.name){case"argon2":case"scrypt":y=()=>!0;break;case"blake2b":case"blake2s":y=(A,B)=>B<=512&&p(A);break;case"blake3":y=(A,B)=>0===B&&p(A);break;case"xxhash64":case"xxhash3":case"xxhash128":y=()=>!1}return yield(()=>B(this,void 0,void 0,(function*(){t||(yield n);const A=t.exports.Hash_GetBuffer(),B=t.exports.memory.buffer;i=new Uint8Array(B,A,C)})))(),{getMemory:()=>i,writeMemory:(A,B=0)=>{i.set(A,B)},getExports:()=>t.exports,setMemorySize:A=>{t.exports.Hash_SetMemorySize(A);const B=t.exports.Hash_GetBuffer(),e=t.exports.memory.buffer;i=new Uint8Array(e,B,A)},init:g,update:E,digest:l,save:()=>{if(!I)throw new Error("save() can only be called after init() and before digest()");const B=t.exports.Hash_GetState(),e=o(),i=t.exports.memory.buffer,n=new Uint8Array(i,B,e),g=new Uint8Array(4+e);return function(A,B){const e=B.length>>1;for(let t=0;t<e;t++){const e=t<<1;A[t]=a(B.charCodeAt(e),B.charCodeAt(e+1))}}(g,A.hash),g.set(n,4),g},load:B=>{if(!(B instanceof Uint8Array))throw new Error("load() expects an Uint8Array generated by save()");const e=t.exports.Hash_GetState(),i=o(),n=4+i,g=t.exports.memory.buffer;if(B.length!==n)throw new Error(`Bad state length (expected ${n} bytes, got ${B.length})`);if(!function(A,B){if(A.length!==2*B.length)return!1;for(let e=0;e<B.length;e++){const t=e<<1;if(B[e]!==a(A.charCodeAt(t),A.charCodeAt(t+1)))return!1}return!0}(A.hash,B.subarray(0,4)))throw new Error("This state was written by an incompatible hash implementation");const Q=B.subarray(4);new Uint8Array(g,e,i).set(Q),I=!0},calculate:(A,B=null,I=null)=>{if(!y(A,B))return g(B),E(A),l("hex",I);const o=r(A);return i.set(o),t.exports.Hash_Calculate(o.length,B,I),Q(c,i,e)},hashLength:e}}))}var p={name:"md5",data:"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",hash:"42fa4d29"};const y=new e;let d=null;A.createMD5=function(){return l(p,16).then((A=>{A.init();const B={init:()=>(A.init(),B),update:e=>(A.update(e),B),digest:B=>A.digest(B),save:()=>A.save(),load:e=>(A.load(e),B),blockSize:64,digestSize:16};return B}))},A.md5=function(A){if(null===d)return function(A,e,t){return B(this,void 0,void 0,(function*(){const B=yield A.lock(),i=yield l(e,t);return B(),i}))}(y,p,16).then((B=>(d=B,d.calculate(A))));try{const B=d.calculate(A);return Promise.resolve(B)}catch(A){return Promise.reject(A)}}}));'+
    'function Hasher(h, type){ this.h=h; this.type=type; };' +
    'Hasher.prototype.append=function(buf){ if("w"===this.type){this.h.update(new Uint8Array(buf));}else{this.h.append(buf)}};'+
    'Hasher.prototype.end=function(){ if("w"===this.type){ var md5Val=this.h.digest();return md5Val; }else{ var md5Val=this.h.end();return md5Val;}};'+
    'Hasher.prototype.reset=function(){ if("w"===this.type){ this.h.init(); } else { this.h.reset(); } };'+
    'var md5Hasher = null; ' +
    'function useSparkMD5(){ md5Hasher=new Hasher(new SparkMD5.ArrayBuffer(), "s"); console.warn(">> hashwasm-md5 init failed, use SparkMD5 instead.") }'+
    'if (typeof WebAssembly === "object" && typeof WebAssembly.instantiate === "function"){'+
    ' try{ hashwasm.createMD5().then(function(m){ m.init(); md5Hasher=new Hasher(m,"w"); self.postMessage({"status":"ready"}); }).catch(function(e){ console.error(e); useSparkMD5(); }) ' +
    ' }catch(e){ useSparkMD5(); }'+
    '} else { useSparkMD5() }'+
    'self.addEventListener("message", function onMsg(e){'+
    ' try {'+
    '  var status = e.data.status, dataBuffer = e.data.dataBuffer;'+
    '  if ("start" === status){ if(md5Hasher !== null) { self.postMessage({"status":"ready"}); } } ' +
    '  else if ("ing" === status){ md5Hasher.append(dataBuffer); } ' +
    '  else if ("end" === status){ if(dataBuffer) { md5Hasher.append(dataBuffer); } var md5=md5Hasher.end(); md5Hasher.reset(); self.postMessage({"md5":md5, "status":"end"}); }'+
    ' }catch(e){ self.postMessage({"status":"error", "error":e.message}); }'+
    '});';


export default Resumable;
