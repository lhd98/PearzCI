using System;
using UnityEngine;
using UnityEngine.Scripting;

namespace Pearz.CI
{
    /// <summary>
    /// GameObject "PearzTool" mà trang web-tool gọi bằng SendMessage.
    /// <see cref="PearzTool"/> tự tạo lúc khởi động bản WebGL; game không cần
    /// đặt component này vào scene.
    /// </summary>
    public sealed class PearzToolReceiver : MonoBehaviour
    {
        [Serializable]
        private sealed class Message
        {
            public string channel;
            public string data;
        }

        // Chỉ được gọi từ JavaScript nên phải giữ lại khi strip code.
        [Preserve]
        public void Receive(string json)
        {
            Message message = null;
            try { message = JsonUtility.FromJson<Message>(json); }
            catch (Exception exception)
            {
                Debug.LogWarning("[Pearz.CI] PearzTool: bad message — " + exception.Message);
            }

            if (message != null) PearzTool.Dispatch(message.channel, message.data);
        }
    }
}
