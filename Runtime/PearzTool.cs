using System;
using System.Collections.Generic;
using UnityEngine;
#if UNITY_WEBGL && !UNITY_EDITOR
using System.Runtime.InteropServices;
#endif

namespace Pearz.CI
{
    /// <summary>
    /// Đầu phía game của cầu nối web-tool (xem Documentation~/webgl-cloudflare-pages.md).
    /// Panel gọi <c>pearzTool.post(channel, data)</c> thì handler đăng ký bằng
    /// <see cref="On"/> nhận <c>data</c>; game gọi <see cref="Emit"/> thì
    /// <c>pearzTool.on(event, ...)</c> của panel nhận. Dữ liệu là chuỗi do game
    /// tự quy ước (thường là JSON), PearzCI không đọc nội dung.
    ///
    /// Chỉ có tác dụng ở bản WebGL chạy trong trang web-tool. Ở Editor và nền
    /// tảng khác <see cref="Emit"/> không làm gì và không tin nào tự tới;
    /// dùng <see cref="Dispatch"/> để giả lập một tin từ panel khi test.
    /// </summary>
    public static class PearzTool
    {
        // Tin tới trước khi game kịp đăng ký handler (panel bấm Play lúc game
        // còn đang khởi động) được giữ lại, tối đa ngần này tin mỗi channel.
        private const int MaxPendingPerChannel = 16;

        private static readonly Dictionary<string, Action<string>> Handlers =
            new Dictionary<string, Action<string>>();
        private static readonly Dictionary<string, Queue<string>> Pending =
            new Dictionary<string, Queue<string>>();

#if UNITY_WEBGL && !UNITY_EDITOR
        [DllImport("__Internal")]
        private static extern void PearzCI_ToolEmit(string eventName, string data);
#endif

        // Project tắt Domain Reload thì static sống qua các lần Play trong Editor.
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.SubsystemRegistration)]
        private static void ResetStatics()
        {
            Handlers.Clear();
            Pending.Clear();
        }

#if UNITY_WEBGL && !UNITY_EDITOR
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        private static void CreateReceiver()
        {
            // Tên GameObject là địa chỉ SendMessage của trang web: phải đúng
            // "PearzTool" như pearzTool.post trong webgl-tool-index.html.
            var receiver = new GameObject("PearzTool");
            receiver.AddComponent<PearzToolReceiver>();
            UnityEngine.Object.DontDestroyOnLoad(receiver);
        }
#endif

        /// <summary>
        /// Nhận tin panel gửi trên <paramref name="channel"/>. Tin đã tới trước
        /// khi đăng ký được giao ngay cho handler đầu tiên của channel.
        /// </summary>
        public static void On(string channel, Action<string> handler)
        {
            if (string.IsNullOrEmpty(channel) || handler == null) return;

            Handlers.TryGetValue(channel, out Action<string> current);
            Handlers[channel] = current + handler;

            if (!Pending.TryGetValue(channel, out Queue<string> queued)) return;
            Pending.Remove(channel);
            while (queued.Count > 0) Invoke(channel, handler, queued.Dequeue());
        }

        public static void Off(string channel, Action<string> handler)
        {
            if (string.IsNullOrEmpty(channel) || handler == null) return;
            if (!Handlers.TryGetValue(channel, out Action<string> current)) return;

            current -= handler;
            if (current == null) Handlers.Remove(channel);
            else Handlers[channel] = current;
        }

        /// <summary>Báo một sự kiện ra panel (<c>pearzTool.on(eventName, ...)</c>).</summary>
        public static void Emit(string eventName, string data = "")
        {
#if UNITY_WEBGL && !UNITY_EDITOR
            if (string.IsNullOrEmpty(eventName)) return;
            PearzCI_ToolEmit(eventName, data ?? string.Empty);
#endif
        }

        /// <summary>
        /// Giao một tin như thể panel vừa gửi. Trang web gọi qua
        /// <see cref="PearzToolReceiver"/>; game gọi trực tiếp khi test trong Editor.
        /// </summary>
        public static void Dispatch(string channel, string data)
        {
            if (string.IsNullOrEmpty(channel)) return;

            if (Handlers.TryGetValue(channel, out Action<string> handler))
            {
                Invoke(channel, handler, data ?? string.Empty);
                return;
            }

            if (!Pending.TryGetValue(channel, out Queue<string> queued))
                Pending[channel] = queued = new Queue<string>();
            if (queued.Count >= MaxPendingPerChannel) queued.Dequeue();
            queued.Enqueue(data ?? string.Empty);
        }

        // Lỗi trong handler của game không được làm hỏng SendMessage của trang.
        private static void Invoke(string channel, Action<string> handler, string data)
        {
            try { handler(data); }
            catch (Exception exception)
            {
                Debug.LogError("[Pearz.CI] PearzTool handler '" + channel + "' failed.");
                Debug.LogException(exception);
            }
        }
    }
}
