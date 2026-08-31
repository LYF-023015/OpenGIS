# NetCDF-4/HDF5 test fixture

`testCFGridWriter.nc4` is an upstream NetCDF-Java test fixture from:

`https://github.com/Unidata/netcdf-java/blob/maint-5.x/cdm/core/src/test/data/testCFGridWriter.nc4`

SHA-256: `c834541e57b06dadc56fbeef22b5841285206666895d52d7c4606d297e1927b5`

It is used only by Java tests to prove that the bundled pure-Java HDF5 reader opens a real
NetCDF-4/HDF5 file. It is not included in the production desktop package.
