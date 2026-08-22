using System.IO;
using UnityEngine;
using UnityEngine.Networking;

namespace Pearz.CI
{
    /// <summary>
    /// Đọc chuỗi build number CI (ví dụ "1.0.0-244") mà pipeline Android nhét
    /// vào StreamingAssets/pearz-build-info.txt lúc build. Auto-load lúc game
    /// khởi động; đọc đồng bộ qua <see cref="Version"/>. Nếu chưa kịp load
    /// hoặc file không tồn tại thì trả chuỗi rỗng — hàm
    /// <see cref="VersionOrFallback"/> tự fall back về
    /// <see cref="Application.version"/>.
    /// </summary>
    public static class PearzBuildInfo
    {
        private const string AssetFileName = "pearz-build-info.txt";

        private static string version = string.Empty;

        /// <summary>
        /// Chuỗi CI version đã load. Trước khi load xong hoặc khi file không
        /// tồn tại (build local trong Editor chẳng hạn) sẽ trả string rỗng.
        /// </summary>
        public static string Version => version;

        /// <summary>
        /// Trả <see cref="Version"/> nếu đã có, ngược lại
        /// <see cref="Application.version"/>. Tiện dùng inline khi hiển thị.
        /// </summary>
        public static string VersionOrFallback =>
            string.IsNullOrEmpty(version) ? Application.version : version;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        private static void AutoLoad()
        {
            version = string.Empty;

            string url = Path.Combine(
                Application.streamingAssetsPath, AssetFileName);

#if UNITY_ANDROID && !UNITY_EDITOR
            // Trên Android StreamingAssets nằm trong APK, phải qua
            // UnityWebRequest. SendWebRequest().completed cho callback async
            // mà không cần MonoBehaviour/coroutine.
            UnityWebRequest request = UnityWebRequest.Get(url);
            var operation = request.SendWebRequest();
            operation.completed += _ =>
            {
                if (request.result == UnityWebRequest.Result.Success)
                {
                    version = (request.downloadHandler.text ?? string.Empty)
                        .Trim();
                }
                request.Dispose();
            };
#else
            // Editor / iOS / Standalone / WebGL: đọc trực tiếp.
            try
            {
                if (File.Exists(url))
                {
                    version = File.ReadAllText(url).Trim();
                }
            }
            catch (System.Exception ex)
            {
                Debug.LogWarning(
                    "[Pearz.CI] Không đọc được " + AssetFileName + ": " +
                    ex.Message);
            }
#endif
        }
    }
}
