package com.example.nutritioninfo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.nutritioninfo.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import java.nio.ByteBuffer
import kotlin.math.min

// Data class to hold parsed nutrient information
data class NutrientData(val name: String, val value: Double, val unit: String)

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        viewBinding.recognizeTextButton.setOnClickListener {
            takePhoto()
        }
        viewBinding.nutriScoreTextView.text = getString(R.string.nutri_score_display_format, "-")
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewBinding.cameraPreviewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder().build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)
            } catch(exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    val buffer: ByteBuffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    imageProxy.close()

                    val image = InputImage.fromBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
                    processImageForText(image)
                }
            }
        )
    }
    
    private fun processImageForText(image: InputImage) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                processTextBlock(visionText)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, getString(R.string.text_recognition_failed_message), e)
                Toast.makeText(this, getString(R.string.text_recognition_failed_message), Toast.LENGTH_SHORT).show()
                viewBinding.nutriScoreTextView.text = getString(R.string.nutri_score_display_format, "Error")
            }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun processTextBlock(result: com.google.mlkit.vision.text.Text) {
        val allRawLineTexts = result.textBlocks.flatMap { block ->
            block.lines.map { line -> line.text }
        }
        Log.d(TAG, "All raw lines from OCR: \n" + allRawLineTexts.joinToString(separator = "\n"))

        val cleanedLines = allRawLineTexts
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("NUTRITIONAL INFORMATION", ignoreCase = true) }

        val nutrientNames = mutableListOf<String>()
        val explicitUnitsFound = mutableListOf<String>()
        val nutrientValuesAsStrings = mutableListOf<String>()

        val valueColumnHeader = "Per 100g"
        val knownExplicitUnits = setOf("kcal", "mg")

        val valueHeaderIndex = cleanedLines.indexOfFirst { it.equals(valueColumnHeader, ignoreCase = true) }

        if (valueHeaderIndex == -1) {
            Log.e(TAG, "'$valueColumnHeader' not found. Cannot parse nutrients correctly.")
            viewBinding.recognizedTextView.text = getString(R.string.error_could_not_find_value_column_header)
            viewBinding.nutriScoreTextView.text = getString(R.string.nutri_score_display_format, "N/A")
            return
        }

        val linesBeforeValueHeader = cleanedLines.subList(0, valueHeaderIndex)
        val linesAfterValueHeader = cleanedLines.subList(valueHeaderIndex + 1, cleanedLines.size)

        for (line in linesAfterValueHeader) {
            if (line.matches(Regex("^[\\d.]+([,\\d]+)*$"))) {
                nutrientValuesAsStrings.add(line.replace(",", ""))
            } else {
                Log.w(TAG, "Non-numeric line found in values section: $line")
            }
        }

        var alreadyProcessingUnits = false
        for (line in linesBeforeValueHeader) {
            val lowerLine = line.lowercase(Locale.getDefault())
            if (knownExplicitUnits.contains(lowerLine)) {
                alreadyProcessingUnits = true
                explicitUnitsFound.add(line)
            } else {
                if (alreadyProcessingUnits) {
                    Log.w(TAG, "Found non-unit line '$line' after explicit units block started. Treating as a name.")
                    nutrientNames.add(line)
                } else {
                    nutrientNames.add(line)
                }
            }
        }

        Log.d(TAG, "Identified Names: $nutrientNames")
        Log.d(TAG, "Identified Explicit Units: $explicitUnitsFound")
        Log.d(TAG, "Identified Values (raw): $nutrientValuesAsStrings")

        val parsedNutrients = mutableListOf<NutrientData>()
        val formattedResultBuilder = StringBuilder()
        val numItems = min(nutrientNames.size, nutrientValuesAsStrings.size)

        val defaultUnitNutrients = setOf(
            "protein", "total carbohydrate", "of which sugars",
            "total fat", "saturated fat", "trans fat", "fibre"
        )

        for (i in 0 until numItems) {
            val name = nutrientNames[i]
            val valueStr = nutrientValuesAsStrings[i]
            val value = valueStr.toDoubleOrNull() ?: 0.0
            var unit = ""

            val lowerName = name.lowercase(Locale.getDefault())
            when {
                lowerName == "energy" && explicitUnitsFound.any { it.equals("kcal", ignoreCase = true) } -> unit = "kcal"
                lowerName == "sodium" && explicitUnitsFound.any { it.equals("mg", ignoreCase = true) } -> unit = "mg"
                defaultUnitNutrients.contains(lowerName) -> unit = "g"
            }
            if (unit.isNotEmpty() || lowerName == "energy") {
                 parsedNutrients.add(NutrientData(name, value, unit))
            }
            formattedResultBuilder.append("$name: $value $unit\n")
            Log.d(TAG, "Formatted: $name: $value $unit")
        }

        if (nutrientNames.size != nutrientValuesAsStrings.size) {
            val mismatchMessage = "Mismatch in names count (${nutrientNames.size}) and values count (${nutrientValuesAsStrings.size})"
            Log.w(TAG, mismatchMessage)
            formattedResultBuilder.append("\n($mismatchMessage)\n")
        }

        if (formattedResultBuilder.toString().isBlank()) {
            viewBinding.recognizedTextView.text = getString(R.string.no_text_found)
        } else {
            viewBinding.recognizedTextView.text = formattedResultBuilder.toString().trim()
        }
        Log.d(TAG, getString(R.string.log_recognized_text_prefix) + result.textBlocks.joinToString("") { it.text })

        val (nutriScore, grade) = calculateNutriScore(parsedNutrients)
        displayNutriScore(grade)
        Log.d(TAG, "Final Nutri-Score: $nutriScore, Grade: $grade")
    }

    // --- Nutri-Score Calculation Logic (General Foods) ---
    private fun getEnergyPointsKJ(kj: Double?): Int { // per 100g
        if (kj == null) return 10
        return when {
            kj > 3350 -> 10
            kj > 3015 -> 9
            kj > 2680 -> 8
            kj > 2345 -> 7
            kj > 2010 -> 6
            kj > 1675 -> 5
            kj > 1340 -> 4
            kj > 1005 -> 3
            kj > 670 -> 2
            kj > 335 -> 1
            else -> 0
        }
    }

    private fun getSugarPoints(g: Double?): Int { // per 100g
        if (g == null) return 10
        return when {
            g > 45 -> 10
            g > 40 -> 9
            g > 36 -> 8
            g > 31 -> 7
            g > 27 -> 6
            g > 22.5 -> 5
            g > 18 -> 4
            g > 13.5 -> 3
            g > 9 -> 2
            g > 4.5 -> 1
            else -> 0
        }
    }

    private fun getSaturatedFatPoints(g: Double?): Int { // per 100g
        if (g == null) return 10
        return when {
            g > 10 -> 10
            g > 9 -> 9
            g > 8 -> 8
            g > 7 -> 7
            g > 6 -> 6
            g > 5 -> 5
            g > 4 -> 4
            g > 3 -> 3
            g > 2 -> 2
            g > 1 -> 1
            else -> 0
        }
    }

    private fun getSodiumPoints(mg: Double?): Int { // per 100g
        if (mg == null) return 10
        return when {
            mg > 900 -> 10
            mg > 810 -> 9
            mg > 720 -> 8
            mg > 630 -> 7
            mg > 540 -> 6
            mg > 450 -> 5
            mg > 360 -> 4
            mg > 270 -> 3
            mg > 180 -> 2
            mg > 90 -> 1
            else -> 0
        }
    }

    private fun getFVLNOPoints(percentage: Double?): Int { // %
        if (percentage == null) return 0
        return when {
            percentage > 80 -> 5
            percentage > 60 -> 2
            percentage > 40 -> 1
            else -> 0
        }
    }

    private fun getFibrePoints(g: Double?): Int { // per 100g
        if (g == null) return 0
        return when {
            g > 4.7 -> 5
            g > 3.7 -> 4
            g > 2.8 -> 3
            g > 1.9 -> 2
            g > 0.9 -> 1
            else -> 0
        }
    }

    private fun getProteinPoints(g: Double?): Int { // per 100g
        if (g == null) return 0
        return when {
            g > 8.0 -> 5
            g > 6.4 -> 4
            g > 4.8 -> 3
            g > 3.2 -> 2
            g > 1.6 -> 1
            else -> 0
        }
    }

    private fun calculateNutriScore(nutrients: List<NutrientData>): Pair<Int, String> {
        val energyKcal = nutrients.find { it.name.equals("Energy", ignoreCase = true) && it.unit.equals("kcal", ignoreCase = true) }?.value
        val energyKJ = energyKcal?.let { it * 4.184 }

        val sugarsG = nutrients.find { it.name.contains("sugars", ignoreCase = true) && it.unit.equals("g", ignoreCase = true) }?.value
        val satFatG = nutrients.find { it.name.contains("saturated fat", ignoreCase = true) && it.unit.equals("g", ignoreCase = true) }?.value
        val sodiumMg = nutrients.find { it.name.equals("Sodium", ignoreCase = true) && it.unit.equals("mg", ignoreCase = true) }?.value
        val fibreG = nutrients.find { it.name.equals("Fibre", ignoreCase = true) && it.unit.equals("g", ignoreCase = true) }?.value
        val proteinG = nutrients.find { it.name.equals("Protein", ignoreCase = true) && it.unit.equals("g", ignoreCase = true) }?.value

        val fvlnoPercentage = 0.0

        Log.d(TAG, "Nutri-Score Inputs: Energy(kJ): $energyKJ, Sugars(g): $sugarsG, SatFat(g): $satFatG, Sodium(mg): $sodiumMg, Fibre(g): $fibreG, Protein(g): $proteinG, FVLNO(%): $fvlnoPercentage")

        val negativePoints = getEnergyPointsKJ(energyKJ) +
                             getSugarPoints(sugarsG) +
                             getSaturatedFatPoints(satFatG) +
                             getSodiumPoints(sodiumMg)

        val fvlnoPoints = getFVLNOPoints(fvlnoPercentage)
        val fibrePoints = getFibrePoints(fibreG)
        val proteinPointsIfNoFVLNOOverride = getProteinPoints(proteinG)

        var positivePoints = fvlnoPoints + fibrePoints

        if (negativePoints < 11) {
            positivePoints += proteinPointsIfNoFVLNOOverride
        } else { 
            if (fvlnoPoints < 5) {
                positivePoints += proteinPointsIfNoFVLNOOverride
            }
        }
        
        val totalScore = if (negativePoints >= 11 && fvlnoPoints == 5) {
                            negativePoints - (fvlnoPoints + fibrePoints)
                         } else {
                            negativePoints - positivePoints
                         }

        Log.d(TAG, "Nutri-Score Points: Negative=$negativePoints (Energy:${getEnergyPointsKJ(energyKJ)}, Sugar:${getSugarPoints(sugarsG)}, SatFat:${getSaturatedFatPoints(satFatG)}, Sodium:${getSodiumPoints(sodiumMg)})")
        Log.d(TAG, "Nutri-Score Points: Positive (initial) = $positivePoints (FVLNO:$fvlnoPoints, Fibre:$fibrePoints, Protein(conditionally):$proteinPointsIfNoFVLNOOverride)")
        Log.d(TAG, "Calculated Total Score: $totalScore")

        val grade = when {
            totalScore <= -1 -> "A"
            totalScore <= 2 -> "B"
            totalScore <= 10 -> "C"
            totalScore <= 18 -> "D"
            else -> "E"
        }
        return Pair(totalScore, grade)
    }

    private fun displayNutriScore(grade: String) {
        viewBinding.nutriScoreTextView.text = getString(R.string.nutri_score_display_format, grade)
        val colorRes = when (grade) {
            "A" -> R.color.nutri_score_a
            "B" -> R.color.nutri_score_b
            "C" -> R.color.nutri_score_c
            "D" -> R.color.nutri_score_d
            "E" -> R.color.nutri_score_e
            else -> android.R.color.transparent
        }
        viewBinding.nutriScoreTextView.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        if (grade == "C") { 
             viewBinding.nutriScoreTextView.setTextColor(Color.BLACK)
        } else {
             viewBinding.nutriScoreTextView.setTextColor(Color.WHITE)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "NutritionInfo"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
