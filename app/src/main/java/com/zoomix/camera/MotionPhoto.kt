package com.zoomix.camera

import java.io.ByteArrayOutputStream

/**
 * Monta uma "Motion Photo": um JPEG normal com um vídeo MP4 curto anexado no final e metadados XMP
 * que dizem onde o vídeo começa. A Galeria do Google (e várias galerias de fabricantes) animam;
 * onde não reconhecerem, aparece só a foto.
 */
object MotionPhoto {
    fun build(jpeg: ByteArray, mp4: ByteArray, presentationUs: Long = 0L): ByteArray {
        val xmp = xmp(mp4.size, presentationUs).toByteArray(Charsets.UTF_8)
        val header = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val segLen = 2 + header.size + xmp.size
        require(segLen <= 0xFFFF) { "XMP grande demais" }

        // Insere o segmento XMP depois do APP0/APP1 (Exif) que já existem no começo do JPEG.
        var pos = 2
        while (pos + 4 <= jpeg.size && (jpeg[pos].toInt() and 0xFF) == 0xFF) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xE0 || marker == 0xE1) {
                val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
                pos += 2 + len
            } else {
                break
            }
        }

        val out = ByteArrayOutputStream(jpeg.size + mp4.size + segLen + 4)
        out.write(jpeg, 0, pos)
        out.write(0xFF)
        out.write(0xE1)
        out.write((segLen shr 8) and 0xFF)
        out.write(segLen and 0xFF)
        out.write(header)
        out.write(xmp)
        out.write(jpeg, pos, jpeg.size - pos)
        out.write(mp4)
        return out.toByteArray()
    }

    private fun xmp(videoLength: Int, presentationUs: Long): String =
        "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Zoomix\">\n" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n" +
            "<rdf:Description rdf:about=\"\"\n" +
            " xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n" +
            " xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n" +
            " xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n" +
            " GCamera:MotionPhoto=\"1\"\n" +
            " GCamera:MotionPhotoVersion=\"1\"\n" +
            " GCamera:MotionPhotoPresentationTimestampUs=\"$presentationUs\"\n" +
            " GCamera:MicroVideo=\"1\"\n" +
            " GCamera:MicroVideoVersion=\"1\"\n" +
            " GCamera:MicroVideoOffset=\"$videoLength\"\n" +
            " GCamera:MicroVideoPresentationTimestampUs=\"$presentationUs\">\n" +
            "<Container:Directory>\n<rdf:Seq>\n" +
            "<rdf:li rdf:parseType=\"Resource\">\n" +
            "<Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/>\n" +
            "</rdf:li>\n" +
            "<rdf:li rdf:parseType=\"Resource\">\n" +
            "<Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"$videoLength\" Item:Padding=\"0\"/>\n" +
            "</rdf:li>\n" +
            "</rdf:Seq>\n</Container:Directory>\n" +
            "</rdf:Description>\n</rdf:RDF>\n</x:xmpmeta>\n<?xpacket end=\"w\"?>"
}
