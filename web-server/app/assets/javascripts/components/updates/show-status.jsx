define(function(require) {
  var _ = require('underscore'),
      SotaDispatcher = require('sota-dispatcher'),
      React = require('react');

  var StatusComponent = React.createClass({
    contextTypes: {
      router: React.PropTypes.func
    },
    componentWillUnmount: function(){
      this.props.UpdateStatus.removeWatch("poll-update-status");
    },
    componentWillMount: function(){
      SotaDispatcher.dispatch({
        actionType: 'get-update-status',
        id: this.context.router.getCurrentParams().id
      });
      this.props.UpdateStatus.addWatch("poll-update-status", _.bind(this.forceUpdate, this, null));
    },
    failedDevicesTable: function() {
      var failedDeviceRows = _.map(this.props.UpdateStatus.deref(), function(value) {
        if(Array.isArray(value)) {
          if(value[2] === "Failed") {
            return (
              <tr key={value[1]}>
                <td>
                  {value[1]}
                </td>
              </tr>
            )
          }
        }
      });
      return (
        <div>
          <div className="row">
            <div className="col-md-12">
              <h2>
                Failed Devices
              </h2>
            </div>
          </div>
          <br/>
          <div className="row">
            <div className="col-xs-12">
              <table className="table table-striped table-bordered">
                <tbody>
                  { failedDeviceRows }
                </tbody>
              </table>
            </div>
          </div>
        </div>
      );
    },
    completedDevicesTable: function() {
      var completedDeviceRows = _.map(this.props.UpdateStatus.deref(), function(value) {
        if(Array.isArray(value)) {
          if(value[2] === "Finished") {
            return (
              <tr key={value[1]}>
                <td>
                  {value[1]}
                </td>
              </tr>
            )
          }
        }
      });
      return (
        <div>
          <div className="row">
            <div className="col-md-12">
              <h2>
                Completed Devices
              </h2>
            </div>
          </div>
          <br/>
          <div className="row">
            <div className="col-xs-12">
              <table className="table table-striped table-bordered">
                <tbody>
                  { completedDeviceRows }
                </tbody>
              </table>
            </div>
          </div>
        </div>
      );
    },
    toggleFailedDevices: function() {
      this.setState({showFailedDevices: !this.state.showFailedDevices});
    },
    toggleCompletedDevices: function() {
      this.setState({showCompletedDevices: !this.state.showCompletedDevices});
    },
    getInitialState: function() {
      return {showFailedDevices: false,
              showCompletedDevices: false
      };
    },
    render: function() {
      var completedDevices = 0;
      var pendingDevices = 0;
      var failedDevices = 0;

      var rows = _.map(this.props.UpdateStatus.deref(), function(value) {
        if(Array.isArray(value)) {
          if(value[2] === "Pending") {
            pendingDevices++;
          } else if (value[2] === "Finished") {
            completedDevices++;
          } else if(value[2] === "Failed") {
            failedDevices++;
          }
          return (
            <tr key={value[1]}>
              <td>
                {value[1]}
              </td>
              <td>
                {value[2]}
              </td>
            </tr>
          );
        }
      });
      return (
        <div>
          <div className="row">
            <div className="col-md-12">
              <h2>
                Status
              </h2>
            </div>
          </div>
          <br/>
          <div className="row">
            <div className="col-xs-12">
              <table className="table table-striped table-bordered">
              <tbody>
                <tr>
                  <td>Pending:</td>
                  <td>{pendingDevices}</td>
                </tr>
                <tr>
                  <td>Completed:</td>
                  <td>{completedDevices}</td>
                </tr>
                <tr>
                  <td>Failed:</td>
                  <td>{failedDevices}</td>
                </tr>
              </tbody>
              </table>
            </div>
          </div>
          <div className="row">
            <div className="col-md-12">
              <h2>
                All Devices
              </h2>
            </div>
          </div>
          <br/>
          <div className="row">
            <div className="col-xs-12">
              <table className="table table-striped table-bordered">
                <tbody>
                  { rows }
                </tbody>
              </table>
            </div>
          </div>
          <button className="btn btn-primary pull-right" onClick={this.toggleFailedDevices}>
            { this.state.showFailedDevices ? "HIDE" : "Show failed Devices" }
          </button>
          <button className="btn btn-primary pull-right" onClick={this.toggleCompletedDevices}>
            { this.state.showCompletedDevices ? "HIDE" : "Show completed Devices" }
          </button>
          { this.state.showFailedDevices ? this.failedDevicesTable() : null }
          { this.state.showCompletedDevices ? this.completedDevicesTable() : null }
        </div>
      );
    }
  });

  return StatusComponent;
});
