define(function(require) {

  var React = require('react'),
      _ = require('underscore'),
      Router = require('react-router'),
      Fluxbone = require('../../mixins/fluxbone'),
      SotaDispatcher = require('sota-dispatcher');

  var ListOfDevices = React.createClass({
    componentWillUnmount: function(){
      this.props.Devices.removeWatch("poll-devices");
    },
    componentWillMount: function(){
      SotaDispatcher.dispatch({actionType: 'search-devices-by-regex', regex: "."});
      this.props.Devices.addWatch("poll-devices", _.bind(this.forceUpdate, this, null));
    },
    render: function() {
      var devices = _.map(this.props.Devices.deref(), function(device) {
        return (
          <tr key={device.uuid}>
            <td>
              <Router.Link to='device' params={{uuid: device.uuid}}>
                {device.deviceId}
              </Router.Link>
            </td>
          </tr>
        );
      });
      return (
        <table className="table table-striped table-bordered">
          <thead>
            <tr>
              <td>
                Device
              </td>
            </tr>
          </thead>
          <tbody>
            { devices }
          </tbody>
        </table>
      );
    }
  });

  return ListOfDevices;
});
