package dev.epegasus.bitmapcompressor

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import dev.epegasus.bitmapcompressor.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private var imageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.mbSelectMain.setOnClickListener { checkStoragePermission() }
        binding.mbCompressMain.setOnClickListener { onCompressClick() }
    }

    private fun checkStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            resultLauncher.launch("image/*")
        } else {
            Toast.makeText(this, "Permission Required! Open settings and allow permission", Toast.LENGTH_SHORT).show()
        }
    }

    private val resultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) {
        imageUri = it

        Glide
            .with(this)
            .load(it)
            .addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                    return false
                }

                override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                    val bmp = binding.sivImage1Main.drawable.toBitmap()
                    val text = "${getFileSize(it)} \n ${bmp.width} * ${bmp.height}"
                    binding.tv1Main.text = text
                    return false
                }

            })
            .into(binding.sivImage1Main)

    }

    private fun getFileSize(it: Uri?): String {
        it?.let { uri ->
            // Check Uri Schema
            when (uri.scheme) {
                ContentResolver.SCHEME_CONTENT -> {
                    val inputStream = contentResolver.openInputStream(it)
                    val size = inputStream?.available()
                    inputStream?.close()
                    return getSizeConvention(size?.toLong())
                }

                ContentResolver.SCHEME_FILE -> {
                    uri.path?.let { path ->
                        val file = File(path)
                        val length = file.length()
                        return getSizeConvention(length)
                    }
                    return "Null: Uri path is not found"
                }

                else -> return "Else: Forgive Me"
            }
        } ?: return ""
    }

    private fun getSizeConvention(size: Long?): String {
        if (size == null || size <= 0) return "0"
        val units = arrayOf("B", "kB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / 1024.0.pow(digitGroups.toDouble())).toString() + " " + units[digitGroups]
    }

    /* --------------------------------------------- Compress --------------------------------------------- */

    private fun onCompressClick() {
        val bitmap = decodeSampledBitmapFromResource(350, 350)
        if (bitmap == null) {
            Toast.makeText(this, "bitmap is null", Toast.LENGTH_SHORT).show()
        }
        binding.sivImage2Main.setImageBitmap(bitmap)
        val text = "${getSizeConvention(bitmap?.allocationByteCount?.toLong())} \n ${bitmap?.width!!} * ${bitmap.height}"
        binding.tv2Main.text = text
    }

    private fun decodeSampledBitmapFromResource(reqWidth: Int, reqHeight: Int): Bitmap? {
        if (imageUri == null) {
            Toast.makeText(this, "Image uri is null", Toast.LENGTH_SHORT).show()
            return null
        }
        if (imageUri!!.path == null) {
            Toast.makeText(this, "Uri path is null", Toast.LENGTH_SHORT).show()
            return null
        }

        val filePath = getFilePathFromUri(this, imageUri!!, true)

        // First decode with inJustDecodeBounds=true to check dimensions
        val bitmap: Bitmap = BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(filePath, this)

            // Calculate inSampleSize
            inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)

            // Decode bitmap with inSampleSize set
            inJustDecodeBounds = false

            BitmapFactory.decodeFile(filePath, this)
        }
        return bitmap
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        // Raw height and width of image
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {

            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }


    internal fun getFilePathFromUri(context: Context, uri: Uri, uniqueName: Boolean): String =
        if (uri.path?.contains("file://") == true) uri.path!!
        else getFileFromContentUri(context, uri, uniqueName).path

    private fun getFileFromContentUri(context: Context, contentUri: Uri, uniqueName: Boolean): File {
        // Preparing Temp file name
        val fileExtension = getFileExtension(context, contentUri) ?: ""
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = ("temp_file_" + if (uniqueName) timeStamp else "") + ".$fileExtension"
        // Creating Temp file
        val tempFile = File(context.cacheDir, fileName)
        tempFile.createNewFile()
        // Initialize streams
        var oStream: FileOutputStream? = null
        var inputStream: InputStream? = null

        try {
            oStream = FileOutputStream(tempFile)
            inputStream = context.contentResolver.openInputStream(contentUri)

            inputStream?.let { copy(inputStream, oStream) }
            oStream.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // Close streams
            inputStream?.close()
            oStream?.close()
        }

        return tempFile
    }

    private fun getFileExtension(context: Context, uri: Uri): String? =
        if (uri.scheme == ContentResolver.SCHEME_CONTENT)
            MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(uri))
        else uri.path?.let {
            MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(File(it)).toString())
        }

    @Throws(IOException::class)
    private fun copy(source: InputStream, target: OutputStream) {
        val buf = ByteArray(8192)
        var length: Int
        while (source.read(buf).also { length = it } > 0) {
            target.write(buf, 0, length)
        }
    }

}