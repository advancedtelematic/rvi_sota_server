define(['react', 'components/vehicles-component', 'components/add-vehicle-component', 'components/search-bar', 'stores/vehicles', 'sota-dispatcher'], function(React, VehiclesComponent, AddVehicleComponent, SearchBar, VehicleStore, SotaDispatcher) {

  var FilterableVehicleComponent = React.createClass({
    toggleAddVin: function() {
      this.setState({showAddVin: !this.state.showAddVin});
    },
    getInitialState: function() {
      return {showAddVin: false};
    },
    render: function() {
      return (
      <div>
        <div className="row">
          <div className="col-md-12">
            <h1>
              Vehicle Identification Numbers
            </h1>
          </div>
        </div>
        <div className="row">
          <div className="col-md-8">
            <p>
            </p>
          </div>
          <div className="col-md-4">
            <button className="btn btn-primary pull-right" onClick={this.toggleAddVin}>
              { this.state.showAddVin ? "HIDE" : "NEW VIN" }
            </button>
          </div>
        </div>
        <br/>
        <div className="row">
          <div className="col-md-12">
            { this.state.showAddVin ? <AddVehicleComponent VehicleStore={VehicleStore}/> : null }
          </div>
        </div>
        <div className="row">
          <div className="col-md-12">
            <SearchBar label="Filter" event="vehicles-filter"/>
            <VehiclesComponent VehicleStore={VehicleStore}/>
          </div>
        </div>
      </div>
    );}
  });

  return FilterableVehicleComponent;

});
