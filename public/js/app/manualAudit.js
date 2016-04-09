/**
 * 
 */
angular.module('manualAuditView', 
		[ 'ui.bootstrap.datetimepicker',
		  'ui.dateTimeInput'
        ])
.factory(
		'MonitorTypeService', [ '$resource', function($resource) {
			return $resource('/MonitorType/:id', {
				id : '@_id'
			});
		} ]).controller(
		'manualAuditCtrl',
		[
				'MonitorTypeService',
				'$scope',
				function($monitorType, $scope) {
					var self = this;
					self.monitorTypeList = $monitorType.query();
					self.activeMt = function(mt) {
						return (mt.measuringBy || mt.measuredBy)
					}

					$scope.dateRangeStart = moment(23, "HH").subtract(2, 'days');
					$scope.dateRangeEnd = moment(23, "HH");
					
					$scope.beforeRenderStartDate = function($view, $dates,
							$leftDate, $upDate, $rightDate) {
						if ($scope.dateRangeEnd) {
							var activeDate = moment($scope.dateRangeEnd);
							for (var i = 0; i < $dates.length; i++) {
								if ($dates[i].localDateValue() >= activeDate
										.valueOf())
									$dates[i].selectable = false;
							}
						}
					}

					$scope.beforeRenderEndDate = function($view, $dates,
							$leftDate, $upDate, $rightDate) {
						if ($scope.dateRangeStart) {
							var activeDate = moment($scope.dateRangeStart)
									.subtract(1, $view).add(1, 'minute');
							for (var i = 0; i < $dates.length; i++) {
								if ($dates[i].localDateValue() <= activeDate
										.valueOf()) {
									$dates[i].selectable = false;
								}
							}
						}
					}
					
					$scope.selectedMtId = "";
					
					self.query = function() {
						console.log($scope.selectedMtId);

					}

				} ]);
