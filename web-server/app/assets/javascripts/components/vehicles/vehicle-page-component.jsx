define(function(require) {

  var React = require('react'),
      Router = require('react-router'),
      AddPackageManually = require('../packages/add-package-manually-component'),
      ListOfPackagesForVin = require('../packages/list-of-packages-for-vin'),
      QueuedPackages = require('../packages/list-of-queued-packages-for-vin'),
      PackageHistory = require('../packages/package-update-history-for-vin'),
      AddComponent = require('../components/add-component-to-vin'),
      ComponentsOnVin = require('../components/list-of-components-on-vin'),
      db = require('stores/db'),
      SotaDispatcher = require('sota-dispatcher'),
      SearchBar = require('../search-bar');

  var VehiclesPageComponent = React.createClass({
    contextTypes: {
      router: React.PropTypes.func
    },
    failAllUpdates: function() {
      SotaDispatcher.dispatch({
        actionType: 'fail-all-updates-by-vin',
        vin: this.props.params.vin
      });
    },
    render: function() {
      var params = this.context.router.getCurrentParams();
      return (
      <div>
        <div>
          <h1>Vehicles &gt; {params.vin}</h1>
        </div>
        <div className="row">
          <div className="col-md-12">
            <button type="button" className="btn btn-primary" onClick={this.failAllUpdates} name="delete-filter">Fail All Pending Updates</button>
            <h2>Installed Packages</h2>
            <ListOfPackagesForVin Packages={db.packagesForVin} Vin={params.vin}/>
            <AddPackageManually Vin={params.vin}/>
            <h2>Installed Components</h2>
            <ComponentsOnVin Components={db.componentsOnVin} Vin={params.vin}/>
            <AddComponent Vin={params.vin}/>
            <h2>Package Updates</h2>
            <QueuedPackages Packages={db.packageQueueForVin} Vin={params.vin}/>
            <PackageHistory Packages={db.packageHistoryForVin} Vin={params.vin}/>
          </div>
        </div>
      </div>
    );}
  });

  return VehiclesPageComponent;

});
