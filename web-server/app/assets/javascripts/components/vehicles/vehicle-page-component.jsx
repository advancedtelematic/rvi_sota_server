define(function(require) {

  var React = require('react'),
      Router = require('react-router'),
      SyncPackages = require('../vehicles/sync-packages'),
      AddPackageManually = require('../packages/add-package-manually-component'),
      ListOfPackagesForVin = require('../packages/list-of-packages-for-vin'),
      QueuedPackages = require('../packages/list-of-queued-packages-for-vin'),
      PackageHistory = require('../packages/package-update-history-for-vin'),
      AddComponent = require('../components/add-component-to-vin'),
      ComponentsOnVin = require('../components/list-of-components-on-vin'),
      db = require('stores/db'),
      SearchBar = require('../search-bar');

  var VehiclesPageComponent = React.createClass({
    contextTypes: {
      router: React.PropTypes.func
    },
    render: function() {
      var params = this.context.router.getCurrentParams();
      return (
      <div>
        <div>
          <h1>VIN: {params.vin}</h1>
        </div>
        <div className="row">
          <div className="col-md-12">
            <AddPackageManually Vin={params.vin}/>
            <SyncPackages Vin={params.vin}/>
            <h2>Installed Packages</h2>
            <ListOfPackagesForVin Packages={db.packagesForVin} Vin={params.vin}/>
            <AddComponent Vin={params.vin}/>
            <h2>Components Install On VIN</h2>
            <ComponentsOnVin Components={db.componentsOnVin} Vin={params.vin}/>
            <h2>Updates</h2>
            <QueuedPackages Packages={db.packageQueueForVin} Vin={params.vin}/>
            <PackageHistory Packages={db.packageHistoryForVin} Vin={params.vin}/>
          </div>
        </div>
      </div>
    );}
  });

  return VehiclesPageComponent;

});
