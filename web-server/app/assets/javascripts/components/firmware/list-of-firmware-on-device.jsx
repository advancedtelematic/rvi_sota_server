define(function(require) {
  var _ = require('underscore'),
      SotaDispatcher = require('sota-dispatcher'),
      React = require('react');

  var FirmwareOnDevice = React.createClass({
    contextTypes: {
      router: React.PropTypes.func
    },
    componentWillUnmount: function(){
      this.props.Firmware.removeWatch("poll-firmware-on-device");
    },
    componentWillMount: function(){
      SotaDispatcher.dispatch({actionType: 'list-firmware-on-device', device: this.props.Device});
      this.props.Firmware.addWatch("poll-firmware-on-device", _.bind(this.forceUpdate, this, null));
    },
    render: function() {
      var firmware = _.map(this.props.Firmware.deref(), function(firmware) {
        return (
          <tr key={firmware.module + '-' + firmware.version_id}>
              <td>
                  {firmware.module}
              </td>
              <td>
                  {firmware.firmwareId}
              </td>
          </tr>
        );
      });
      return (
        <table className="table table-striped table-bordered">
          <thead>
            <tr>
              <td>
                Module Name
              </td>
              <td>
                Version ID
              </td>
            </tr>
          </thead>
          <tbody>
            { firmware }
          </tbody>
        </table>
      );
    }
  });

  return FirmwareOnDevice;
});
