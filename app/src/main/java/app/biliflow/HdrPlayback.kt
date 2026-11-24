package app.biliflow

import org.json.JSONArray
import org.json.JSONObject

/** Screen DV branding alone is not a decoder or base-layer capability check. */
fun canPlayHdrRepresentation(rep: JSONObject, hdrDisplay: Boolean, nativeDecoder: (JSONObject) -> Boolean,
    compatibleDecoder: (JSONObject) -> Boolean, sdrOnly: Boolean = false, dolbyDisplay: Boolean = false): Boolean = when (rep.optInt("id")) {
    125 -> !sdrOnly && hdrDisplay && compatibleDecoder(rep)
    // Native decoders may tone-map on outputs without a DV display flag.
    // Media3 only offers base decoders for profiles with a compatible base layer.
    126 -> !sdrOnly && (dolbyDisplay || nativeDecoder(rep) || (hdrDisplay && compatibleDecoder(rep)))
    else -> true
}

fun filterHdrStreams(data: JSONObject, supported: (JSONObject) -> Boolean): JSONObject = JSONObject(data.toString()).apply {
    optJSONObject("dash")?.let { dash ->
        dash.put("video", JSONArray(dash.optJSONArray("video")?.objects().orEmpty().filter(supported)))
    }
}

fun isVideoDecoderFailure(code: Int): Boolean = code in 4000..4999
