package org.opengis.gis.raster;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import javax.imageio.ImageIO;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.gis.crs.CrsService;
import org.opengis.tool.context.CancellationToken;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.nc2.Attribute;
import ucar.nc2.write.NetcdfFormatWriter;

class RasterServiceTest {
  @Test
  void readsStylesCachesAndRevisionsGeoTiff(@TempDir Path workspace) throws Exception {
    Path path = workspace.resolve("surface.tif");
    BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
    for (int y = 0; y < 8; y++) {
      for (int x = 0; x < 8; x++) image.getRaster().setSample(x, y, 0, x + y * 8);
    }
    var envelope = new ReferencedEnvelope(-180, 180, -85, 85, CRS.decode("EPSG:4326", true));
    var coverage = new GridCoverageFactory().create("surface", image, envelope);
    GeoTiffWriter writer = new GeoTiffWriter(path.toFile());
    try {
      writer.write(coverage);
    } finally {
      writer.dispose();
    }

    RasterService service = new RasterService(new CrsService());
    RasterRegistration registration =
        service.register(path, RasterStyle.defaults(), new CancellationToken());
    assertThat(registration.info().width()).isEqualTo(8);
    assertThat(registration.info().bandCount()).isEqualTo(1);
    byte[] first = service.renderTile(registration.rasterId(), 0, 0, 0);
    byte[] cached = service.renderTile(registration.rasterId(), 0, 0, 0);
    assertThat(first).isEqualTo(cached).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    assertThat(service.cacheStats().get("entries")).isEqualTo(1);

    RasterRegistration updated =
        service.updateStyle(registration.rasterId(), Map.of("ramp", "terrain", "opacity", 0.5));
    assertThat(updated.styleRevision()).isEqualTo(1);
    assertThat(service.cacheStats().get("entries")).isEqualTo(0);
  }

  @Test
  void readsAsciiGridNetCdfAndJpeg2000WithBundledJavaReaders(@TempDir Path workspace)
      throws Exception {
    RasterService service = new RasterService(new CrsService());

    Path ascii = workspace.resolve("surface.asc");
    Files.writeString(
        ascii,
        """
        ncols 3
        nrows 2
        xllcorner -180
        yllcorner -90
        cellsize 1
        NODATA_value -9999
        1 2 3
        4 5 6
        """,
        StandardCharsets.US_ASCII);
    Files.writeString(
        workspace.resolve("surface.prj"),
        CRS.decode("EPSG:4326", true).toWKT(),
        StandardCharsets.UTF_8);
    RasterInfo asciiInfo = service.inspect(ascii, new CancellationToken());
    assertThat(asciiInfo.driver()).isEqualTo("ASCII Grid");
    assertThat(asciiInfo.width()).isEqualTo(3);
    assertThat(asciiInfo.height()).isEqualTo(2);

    Path netcdf = workspace.resolve("surface.nc");
    var builder = NetcdfFormatWriter.createNewNetcdf3(netcdf.toString());
    builder.addDimension("lat", 2);
    builder.addDimension("lon", 3);
    builder
        .addVariable("lat", DataType.DOUBLE, "lat")
        .addAttribute(new Attribute("units", "degrees_north"));
    builder
        .addVariable("lon", DataType.DOUBLE, "lon")
        .addAttribute(new Attribute("units", "degrees_east"));
    builder.addVariable("surface", DataType.FLOAT, "lat lon");
    builder.addAttribute(new Attribute("Conventions", "CF-1.8"));
    try (NetcdfFormatWriter writer = builder.build()) {
      writer.write("lat", Array.factory(DataType.DOUBLE, new int[] {2}, new double[] {-1.0, 1.0}));
      writer.write(
          "lon", Array.factory(DataType.DOUBLE, new int[] {3}, new double[] {10.0, 11.0, 12.0}));
      writer.write(
          "surface",
          Array.factory(DataType.FLOAT, new int[] {2, 3}, new float[] {1, 2, 3, 4, 5, 6}));
    }
    RasterInfo netcdfInfo = service.inspect(netcdf, new CancellationToken());
    assertThat(netcdfInfo.driver()).isEqualTo("NetCDF");
    assertThat(netcdfInfo.width()).isEqualTo(3);
    assertThat(netcdfInfo.height()).isEqualTo(2);

    Path hdf5 = workspace.resolve("surface.h5");
    try (var source = getClass().getResourceAsStream("/raster/testCFGridWriter.nc4")) {
      assertThat(source).as("bundled NetCDF-4/HDF5 fixture").isNotNull();
      Files.copy(source, hdf5, StandardCopyOption.REPLACE_EXISTING);
    }
    RasterInfo hdf5Info = service.inspect(hdf5, new CancellationToken());
    assertThat(hdf5Info.driver()).isEqualTo("HDF5/NetCDF-4");
    assertThat(hdf5Info.width()).isPositive();
    assertThat(hdf5Info.height()).isPositive();

    Path jpeg2000 = workspace.resolve("surface.jp2");
    BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_BYTE_GRAY);
    assertThat(ImageIO.write(image, "JPEG2000", jpeg2000.toFile())).isTrue();
    RasterInfo jpegInfo = service.inspect(jpeg2000, new CancellationToken());
    assertThat(jpegInfo.driver()).isEqualTo("JPEG2000");
    assertThat(jpegInfo.width()).isEqualTo(4);
    assertThat(jpegInfo.height()).isEqualTo(3);
  }
}
