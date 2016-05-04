define(function(require) {
  var React = require('react'),
      AddDeviceComponent = require('./add-device-component');

  var DevicesHeaderComponent = React.createClass({
    render: function() {
      return (
      <div>
        <div className="row">
          <div className="col-md-12">
            <h1>
              Devices
            </h1>
          </div>
        </div>
        <div className="row">
          <div className="col-md-8">
            <p>
            </p>
          </div>
        </div>
        <AddDeviceComponent />
      </div>
    );}
  });

  return DevicesHeaderComponent;

});
