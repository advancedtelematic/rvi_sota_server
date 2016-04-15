define(function(require) {
  var atom = require('../lib/atom');

  var DB = (function() {
    function DB() {
      this.updates = atom.createAtom([
      ]);
      this.showUpdate = atom.createAtom({});
      this.updateStatus = atom.createAtom({});

      this.packagesForFilter = atom.createAtom([]);
      this.packagesForDevice = atom.createAtom([]);
      this.packageQueueForDevice = atom.createAtom([]);
      this.packageHistoryForDevice = atom.createAtom([]);
      this.componentsOnDevice = atom.createAtom([]);
      this.firmwareOnDevice = atom.createAtom([]);
      this.filtersForPackage = atom.createAtom([]);
      this.devicesForPackage = atom.createAtom([]);
      this.devicesQueuedForPackage = atom.createAtom([]);

      this.packages = atom.createAtom([]);
      this.showPackage = atom.createAtom({});
      this.searchablePackages = atom.createAtom([]);

      this.filters = atom.createAtom([]);
      this.searchableFilters = atom.createAtom([]);
      this.showFilter = atom.createAtom({});

      this.searchableComponents = atom.createAtom([]);
      this.showComponent = atom.createAtom({});
      this.devicesForComponent = atom.createAtom([]);

      this.affectedDevices = atom.createAtom([]);
      this.searchableDevices = atom.createAtom([]);
      this.postStatus = atom.createAtom([]);
    }

    return DB;
  })();

  return new DB();

});
