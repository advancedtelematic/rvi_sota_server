define(function(require) {

  var React = require('react'),
      Router = require('react-router'),
      SyncPackages = require('../devices/sync-packages'),
      AddPackageManually = require('../packages/add-package-manually-component'),
      ListOfPackages = require('../packages/list-of-packages'),
      QueuedPackages = require('../packages/list-of-queued-packages-for-device'),
      PackageHistory = require('../packages/package-update-history-for-device'),
      AddComponent = require('../components/add-component-to-device'),
      ComponentsOnDevice = require('../components/list-of-components-on-device'),
      FirmwareOnDevice = require('../firmware/list-of-firmware-on-device'),
      db = require('stores/db'),
      SearchBar = require('../search-bar');

  var DevicePageComponent = React.createClass({
    contextTypes: {
      router: React.PropTypes.func
    },
    render: function() {
      var params = this.context.router.getCurrentParams();
      return (
      <div>
        <div>
          <h1>Devices &gt; {params.device}</h1>
        </div>
        <div className="row">
          <div className="col-md-12">
            <h2>Installed Packages</h2>
            <ListOfPackages
              Packages={db.packagesForDevice}
              PollEventName="poll-packages"
              DispatchObject={{actionType: 'get-packages-for-device', device: params.deviceId}}
              DisplayCampaignLink={false}/>
            <AddPackageManually Device={params.deviceId}/>
            <SyncPackages Device={params.deviceId}/>
            <h2>Installed Firmware</h2>
            <FirmwareOnDevice Firmware={db.firmwareOnDevice} Device={params.deviceId}/>
            <h2>Installed Components</h2>
            <ComponentsOnDevice Components={db.componentsOnDevice} Device={params.deviceId}/>
            <AddComponent Device={params.deviceId}/>
            <h2>Package Updates</h2>
            <QueuedPackages Packages={db.packageQueueForDevice} Device={params.deviceId}/>
            <PackageHistory Packages={db.packageHistoryForDevice} Device={params.deviceId}/>
          </div>
        </div>
      </div>
    );}
  });

  return DevicePageComponent;

});
