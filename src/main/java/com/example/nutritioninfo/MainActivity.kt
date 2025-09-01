package com.example.nutritioninfo

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.nutritioninfo.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Load the sample image and display it
        try {
            assets.open(getString(R.string.nutritional_label_jpg_file_name)).use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                viewBinding.imageView.setImageBitmap(bitmap)
            }
        } catch (e: IOException) {
            Log.e(TAG, getString(R.string.error_loading_sample_image), e)
        }

        viewBinding.recognizeTextButton.setOnClickListener {
            recognizeTextFromSampleImage()
        }
    }

    private fun recognizeTextFromSampleImage() {
        try {
            assets.open(getString(R.string.nutritional_label_jpg_file_name)).use { inputStream ->
                val bitmap = BitmapFactory.decodeStream(inputStream)
                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        processTextBlock(visionText)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, getString(R.string.text_recognition_failed_message), e)
                        Toast.makeText(this, getString(R.string.text_recognition_failed_message), Toast.LENGTH_SHORT).show()
                    }
            }
        } catch (e: IOException) {
            Log.e(TAG, getString(R.string.error_processing_sample_image), e)
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
    val explicitUnitsFound = mutableListOf<String>() // Stores "kcal", "mg" in order
    val nutrientValues = mutableListOf<String>()

    val valueColumnHeader = "Per 100g"
    val knownExplicitUnits = setOf("kcal", "mg") // lowercase

    val valueHeaderIndex = cleanedLines.indexOfFirst { it.equals(valueColumnHeader, ignoreCase = true) }

    if (valueHeaderIndex == -1) {
        Log.e(TAG, "'$valueColumnHeader' not found. Cannot parse nutrients correctly.")
        viewBinding.recognizedTextView.text = getString(R.string.error_could_not_find_value_column_header)
        return
    }

    val linesBeforeValueHeader = cleanedLines.subList(0, valueHeaderIndex)
    val linesAfterValueHeader = cleanedLines.subList(valueHeaderIndex + 1, cleanedLines.size)

    // Populate nutrientValues
    for (line in linesAfterValueHeader) {
        if (line.matches(Regex("^[\\d.]+$"))) {
            nutrientValues.add(line)
        } else {
            Log.w(TAG, "Non-numeric line found in values section: $line")
        }
    }

    // Populate nutrientNames and explicitUnitsFound from linesBeforeValueHeader
    var alreadyProcessingUnits = false
    for (line in linesBeforeValueHeader) {
        val lowerLine = line.lowercase(Locale.getDefault())
        if (knownExplicitUnits.contains(lowerLine)) {
            alreadyProcessingUnits = true
            explicitUnitsFound.add(line) // Store original case
        } else {
            if (alreadyProcessingUnits) {
                Log.w(TAG, "Found non-unit line '$line' after explicit units block started. Treating as a name.")
                 // Decide if this should be an error or if names can be interspersed.
                 // For now, if we started units, non-units are unexpected before value header.
                 // Adding to names for now, but this might indicate OCR issues or complex layout.
                nutrientNames.add(line)

            } else {
                nutrientNames.add(line)
            }
        }
    }

    Log.d(TAG, "Identified Names: $nutrientNames")
    Log.d(TAG, "Identified Explicit Units: $explicitUnitsFound")
    Log.d(TAG, "Identified Values: $nutrientValues")

    val formattedResultBuilder = StringBuilder()
    val numItems = minOf(nutrientNames.size, nutrientValues.size)

    val defaultUnitNutrients = setOf( // lowercase for matching
        "protein", "total carbohydrate", "of which sugars",
        "total fat", "saturated fat", "trans fat"
    )

    for (i in 0 until numItems) {
        val name = nutrientNames[i]
        val value = nutrientValues[i]
        var unit = "" // Default to no unit

        // Assign units based on nutrient name
        val lowerName = name.lowercase(Locale.getDefault())
        when {
            lowerName == "energy" && explicitUnitsFound.any { it.equals("kcal", ignoreCase = true) } -> unit = "kcal"
            lowerName == "sodium" && explicitUnitsFound.any { it.equals("mg", ignoreCase = true) } -> unit = "mg"
            defaultUnitNutrients.contains(lowerName) -> unit = "g"
        }
        
        formattedResultBuilder.append("$name: $value $unit\n")
        Log.d(TAG, "Formatted: $name: $value $unit")
    }
    
    if (nutrientNames.size != nutrientValues.size) {
        val mismatchMessage = "Mismatch in names count (${nutrientNames.size}) and values count (${nutrientValues.size})"
        Log.w(TAG, mismatchMessage)
        formattedResultBuilder.append("\n($mismatchMessage)\n")
        // Optionally append remaining names/values if desired for debugging
        if (nutrientNames.size > numItems) {
            formattedResultBuilder.append("Unmatched Names:\n")
            for (i in numItems until nutrientNames.size) formattedResultBuilder.append("${nutrientNames[i]}\n")
        }
         if (nutrientValues.size > numItems) {
            formattedResultBuilder.append("Unmatched Values:\n")
            for (i in numItems until nutrientValues.size) formattedResultBuilder.append("${nutrientValues[i]}\n")
        }
    }

    if (formattedResultBuilder.toString().isBlank()) {
        viewBinding.recognizedTextView.text = getString(R.string.no_text_found)
    } else {
        viewBinding.recognizedTextView.text = formattedResultBuilder.toString().trim()
    }
    Log.d(TAG, getString(R.string.log_recognized_text_prefix) + result.textBlocks.joinToString("") { it.text })
}

    companion object {
        private const val TAG = "NutritionInfo" // It's common practice to keep Log TAGs as const in the class.
    }
}
