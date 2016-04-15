define(function(require) {

  var React = require('react'),
      ListOfDevices = require('./list-of-devices'),
      DevicesHeaderComponent = require('./devices-header-component'),
      db = require('stores/db'),
      Errors = require('../errors'),
      SearchBar = require('../search-bar');

  var DevicesPageComponent = React.createClass({
    render: function() {
      return (
      <div>
        <div>
          <DevicesHeaderComponent/>
        </div>
        <div className="row">
          <div className="col-md-12">
            <Errors />
            <SearchBar label="Filter" event="search-devices-by-regex"/>
            <ListOfDevices Devices={db.searchableDevices}/>
          </div>
        </div>
      </div>
    );}
  });

  return DevicesPageComponent;

});
