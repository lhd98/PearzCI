// Pearz.CI.PearzTool.Emit: báo một sự kiện từ game ra trang. window.pearzTool
// do webgl-index.html của PearzCI tạo; trang khác không có thì bỏ qua.
mergeInto(LibraryManager.library, {
    PearzCI_ToolEmit: function (eventName, data) {
        if (window.pearzTool) {
            window.pearzTool.emit(UTF8ToString(eventName), UTF8ToString(data));
        }
    }
});
