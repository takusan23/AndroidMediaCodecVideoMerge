package io.github.takusan23.androidmediacodecvideomerge

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * 動画保存先
 *
 * /sdcard/Android/data/io.github.takusan23.androidmediacodecvideomerge/files/video
 * */
class MainActivity : AppCompatActivity() {
    /** 動画ファイルがあるフォルダ名 */
    private val FOLDER_NAME = "bakkure"

    /** 映像 ファイル名 */
    private val MERGE_VIDEO_FILE_NAME = "video_merge.mp4"

    /** 音声 ファイル名 */
    private val MERGE_AUDIO_FILE_NAME = "audio_merge.aac"

    /** 映像と音声を合わせたファイル */
    private val FINAL_RESULT_FILE = "final_merge.mp4"

    /** 一時ファイルの名前 */
    private val TEMP_RAW_DATA_FILE_NAME = "temp_raw_data"

    /** 映像くっつけるやつ */
    private lateinit var videoDataMerge: VideoDataMerge

    /** 音声くっつけるやつ */
    private lateinit var audioDataMerge: AudioDataMerge

    /** 時間を表示するTextView */
    private val textView by lazy { findViewById<TextView>(R.id.activity_main_text_view) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 結合したい動画ファイルが置いてあるフォルダ
        val videoFolder = File(getExternalFilesDir(null), FOLDER_NAME).apply {
            if (!exists()) {
                mkdir()
            }
        }

        // 最終的に結合するファイル。映像
        val videoMergedFile = File(getExternalFilesDir(null), MERGE_VIDEO_FILE_NAME).apply {
            if (!exists()) {
                delete()
            }
            createNewFile()
        }

        // 最終的に結合するファイル。音声
        val audioMergedFile = File(getExternalFilesDir(null), MERGE_AUDIO_FILE_NAME).apply {
            if (!exists()) {
                delete()
            }
            createNewFile()
        }

        // 音声だけの生データをおいておくファイル
        val tempRawDataFile = File(getExternalFilesDir(null), TEMP_RAW_DATA_FILE_NAME).apply {
            if (!exists()) {
                delete()
            }
            createNewFile()
        }

        // 最終的なファイル
        val finalResultFile = File(getExternalFilesDir(null), FINAL_RESULT_FILE).apply {
            if (!exists()) {
                delete()
            }
            createNewFile()
        }


        // 数字を見つける正規表現
        val numberRegex = "(\\d+)".toRegex()
        // 結合する動画ファイルを配列
        val videoList = videoFolder.listFiles()
            // ?.filter { it.extension == "ts" } // これ動画ファイル以外が入ってくる場合はここで見切りをつける
            ?.toList()
            ?.sortedBy { numberRegex.find(it.name)?.groupValues?.get(0)?.toIntOrNull() ?: 0 } // 数字の若い順にする
        // ?.take(2)

        // インスタンス作成
        videoDataMerge = VideoDataMerge(videoList!!, videoMergedFile /*bitRate = 1_000_000, frameRate = 30*/)
        audioDataMerge = AudioDataMerge(videoList!!, audioMergedFile, tempRawDataFile, bitRate = 192_000)

        // 別スレッドを起動して開始
        // 音声と映像をそれぞれ並列で実行したほうがいいと思います...（デコーダーの起動制限に引っかからなければ）
        // 今回はコルーチン入れてないので直列で行います...
        thread {
            // 映像デコード
            val videoStartMs = System.currentTimeMillis()
            showMessage("映像開始：$videoStartMs")
            videoDataMerge.merge()
            showMessage("映像終了：${System.currentTimeMillis() - videoStartMs} Ms")

            // 音声デコード
            val audioStartMs = System.currentTimeMillis()
            showMessage("音声開始：$audioStartMs")
            audioDataMerge.merge()
            showMessage("音声終了：${System.currentTimeMillis() - audioStartMs} Ms")

            //  // 合成...
            MergedDataMuxer.mixed(finalResultFile, listOf(audioMergedFile, videoMergedFile))
            showMessage("合成終了：${System.currentTimeMillis() - videoStartMs} Ms")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoDataMerge.stop()
        audioDataMerge.stop()
    }

    private fun showMessage(message: String) {
        println(message)
        runOnUiThread {
            textView.append("\n$message")
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }
}